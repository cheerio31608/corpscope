package io.corpscope.external.dart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.*;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DartHttpClientTest {
    private MockRestServiceServer server;
    private DartHttpClient client;
    private final String key = "a".repeat(40);
    private final FinancialPeriod period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);

    @BeforeEach void setUp() {
        var builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DartHttpClient(builder.build(), new ObjectMapper(),
                new DartProperties("https://opendart.fss.or.kr/api", key, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test void noDataIsAnExplicitResult() {
        server.expect(requestTo(containsString("fnlttSinglAcntAll.json")))
                .andExpect(queryParam("fs_div", "CFS")).andExpect(queryParam("reprt_code", "11011"))
                .andRespond(withSuccess("{\"status\":\"013\",\"message\":\"없음\"}", MediaType.APPLICATION_JSON));
        assertThat(client.financials("00126380", period).data().status()).isEqualTo("013");
        server.verify();
    }

    @Test void quotaIsPermanentAndSanitized() {
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"020\",\"message\":\"limit\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.financials("00126380", period))
                .isExactlyInstanceOf(DartException.class).hasMessageContaining("020").hasMessageNotContaining(key).hasNoCause();
    }

    @Test void serverErrorsAreRetryableAndDoNotExposeRequestUrl() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.company("00126380")).isInstanceOf(DartException.Transient.class)
                .hasMessage("DART HTTP 503").hasNoCause();
    }

    @Test void networkTimeoutIsRetryable() {
        server.expect(anything()).andRespond(request -> { throw new java.net.SocketTimeoutException("secret " + key); });
        assertThatThrownBy(() -> client.company("00126380")).isInstanceOf(DartException.Transient.class)
                .hasMessageNotContaining(key).hasNoCause();
    }

    @Test void malformedPayloadIsPermanent() {
        server.expect(anything()).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.company("00126380")).isExactlyInstanceOf(DartException.class).hasNoCause();
    }

    @Test void missingKeyFailsBeforeAnyRequest() {
        var noKey = new DartHttpClient(RestClient.create(), new ObjectMapper(),
                new DartProperties("", "", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThatThrownBy(() -> noKey.company("00126380")).hasMessageContaining("OPENDART_API_KEY");
    }

    @Test void filingSearchIncludesCorrectionsAndPreservesRawDateEvidence() {
        server.expect(requestTo(containsString("list.json")))
                .andExpect(queryParam("last_reprt_at", "N"))
                .andExpect(queryParam("pblntf_detail_ty", "A001"))
                .andExpect(queryParam("bgn_de", "20160101"))
                .andExpect(queryParam("end_de", "20261007"))
                .andExpect(queryParam("page_no", "2"))
                .andRespond(withSuccess("""
                    {"status":"000","page_no":2,"total_page":2,"total_count":101,
                     "list":[{"corp_code":"00000001","rcept_no":"20250331000001",
                       "rcept_dt":"20250331","report_nm":"[기재정정]사업보고서 (2024.12)"}]}
                    """, MediaType.APPLICATION_JSON));
        var result = client.annualFilings("00000001", java.time.LocalDate.of(2026,10,7),2);
        assertThat(result.data().list().getFirst().receiptDate()).isEqualTo("20250331");
        assertThat(result.rawJson()).contains("rcept_dt");
        server.verify();
    }

    @Test void quarterlySearchLimitsDateAndIncludesCorrectionCandidates() {
        server.expect(requestTo(containsString("list.json")))
                .andExpect(queryParam("last_reprt_at", "N"))
                .andExpect(queryParam("pblntf_detail_ty", "A003"))
                .andExpect(queryParam("bgn_de", "20251114"))
                .andExpect(queryParam("end_de", "20251114"))
                .andExpect(queryParam("page_no", "1"))
                .andRespond(withSuccess("""
                    {"status":"000","page_no":1,"total_page":1,"total_count":1,
                     "list":[{"corp_code":"00126380","rcept_no":"20251114002447",
                       "rcept_dt":"20251114","report_nm":"분기보고서 (2025.09)"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(client.quarterlyFilings("00126380",java.time.LocalDate.of(2025,11,14),1)
                .data().list().getFirst().receiptNumber()).isEqualTo("20251114002447");
        server.verify();
    }

    @Test void halfYearSearchUsesA002AndRetainsCorrectionCandidates() {
        server.expect(requestTo(containsString("list.json")))
                .andExpect(queryParam("pblntf_detail_ty", "A002"))
                .andExpect(queryParam("last_reprt_at", "N"))
                .andExpect(queryParam("bgn_de", "20250630"))
                .andExpect(queryParam("end_de", "20250930"))
                .andRespond(withSuccess("""
                    {"status":"000","page_no":1,"total_page":1,"total_count":1,
                     "list":[{"corp_code":"00126380","rcept_no":"20250814000001",
                       "rcept_dt":"20250814","report_nm":"반기보고서 (2025.06)"}]}
                    """, MediaType.APPLICATION_JSON));
        assertThat(client.periodicFilings("00126380",ReportType.HALF_YEAR,
                java.time.LocalDate.of(2025,6,30),java.time.LocalDate.of(2025,9,30),1)
                .data().list()).hasSize(1);
        server.verify();
    }

    @Test void documentDownloadPreservesZipBytesAndUsesExactReceipt() {
        byte[] zip={80,75,3,4,0,0};
        server.expect(requestTo(containsString("document.xml"))).andExpect(queryParam("rcept_no","20170331004820"))
                .andRespond(withSuccess(zip,MediaType.APPLICATION_OCTET_STREAM));
        assertThat(client.document("20170331004820")).containsExactly(zip);
        server.verify();
    }

    @Test void documentStatusXmlDoesNotLeakServerBodyOrAuthenticationKey() {
        server.expect(anything()).andRespond(withSuccess("<result><status>020</status><message>"+key+"</message></result>",MediaType.APPLICATION_XML));
        assertThatThrownBy(() -> client.document("20170331004820")).isInstanceOf(DartException.class)
                .hasMessageNotContaining(key).hasNoCause();
    }
}
