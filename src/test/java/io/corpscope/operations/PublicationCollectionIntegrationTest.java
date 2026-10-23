package io.corpscope.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.external.dart.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All filing records here are synthetic test evidence in a disposable database. */
@Tag("integration") @Testcontainers @ActiveProfiles("demo") @SpringBootTest
class PublicationCollectionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    DartGateway gateway;
    PublicationCollectionService service;
    long companyId;
    final LocalDate end = LocalDate.of(2026,10,7);

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM periodic_filing_coverage");
        jdbc.update("DELETE FROM quarterly_filing_coverage");
        jdbc.update("DELETE FROM report_publication");
        jdbc.update("DELETE FROM raw_ingestion");
        jdbc.update("DELETE FROM company");
        companyId = jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00000001','Synthetic filing test') RETURNING id", Long.class);
        gateway = mock(DartGateway.class);
        service = new PublicationCollectionService(gateway,jdbc,transactions);
    }

    @Test void collectionIsIdempotentAndPreservesOfficialDateEvidence() throws Exception {
        var payload = response(1,1,1,"00000001","[기재정정]사업보고서 (2024.12)");
        when(gateway.annualFilings("00000001",end,1)).thenReturn(payload);
        service.collectCompany(companyId,"00000001",end);
        var first = jdbc.queryForMap("SELECT * FROM report_publication");
        service.collectCompany(companyId,"00000001",end);
        assertThat(jdbc.queryForMap("SELECT * FROM report_publication")).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
        assertThat(first.get("correction_marker")).isEqualTo(true);
        assertThat(first.get("receipt_date").toString()).isEqualTo("2025-03-31");
        assertThat(jdbc.queryForObject("SELECT endpoint FROM raw_ingestion",String.class)).isEqualTo("list");
    }

    @Test void duplicateReceiptAcrossPagesLeavesNoPartialMetadata() throws Exception {
        when(gateway.annualFilings("00000001",end,1)).thenReturn(response(1,2,2,"00000001","사업보고서 (2024.12)"));
        when(gateway.annualFilings("00000001",end,2)).thenReturn(response(2,2,2,"00000001","사업보고서 (2024.12)"));
        assertThatThrownBy(() -> service.collectCompany(companyId,"00000001",end)).isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isZero();
    }

    @Test void wrongCompanyFailsBeforeWriting() throws Exception {
        when(gateway.annualFilings("00000001",end,1)).thenReturn(response(1,1,1,"00000002","사업보고서 (2024.12)"));
        assertThatThrownBy(() -> service.collectCompany(companyId,"00000001",end)).isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_publication",Integer.class)).isZero();
    }

    @Test void conflictingPreviouslyVerifiedMetadataRollsBackTheNewRawPage() throws Exception {
        when(gateway.annualFilings("00000001",end,1)).thenReturn(response(1,1,1,"00000001","사업보고서 (2024.12)"));
        service.collectCompany(companyId,"00000001",end);
        when(gateway.annualFilings("00000001",end,1)).thenReturn(response(1,1,1,"00000001","[기재정정]사업보고서 (2024.12)"));
        assertThatThrownBy(() -> service.collectCompany(companyId,"00000001",end)).isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT correction_marker FROM report_publication",Boolean.class)).isFalse();
    }

    @Test void quarterlyPilotStoresExactReceiptAndRejectsDifferentReceipt() throws Exception {
        when(gateway.source()).thenReturn("OPENDART");
        var quarterly = new QuarterlyPublicationCollectionService(gateway,jdbc,transactions);
        var date = LocalDate.of(2025,11,14);
        var data = new DartFilingsResponse("000",1,1,1,List.of(
                new DartFilingsResponse.Filing("00000001","20251114000001","20251114","분기보고서 (2025.09)")));
        when(gateway.quarterlyFilings("00000001",date,1))
                .thenReturn(new ApiPayload<>(data,mapper.writeValueAsString(data)));
        assertThatThrownBy(() -> quarterly.collect("00000001","20251114000002",date))
                .isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isZero();
        quarterly.collect("00000001","20251114000001",date);
        quarterly.collect("00000001","20251114000001",date);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_publication",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT report_title FROM report_publication",String.class))
                .isEqualTo("분기보고서 (2025.09)");
    }

    @Test void boundedQuarterlyCoverageIsAtomicAndIdempotent() throws Exception {
        jdbc.update("UPDATE company SET corp_code='00126380' WHERE id=?",companyId);
        when(gateway.source()).thenReturn("OPENDART");
        var through=LocalDate.of(2025,12,31);
        var data=new DartFilingsResponse("000",1,1,1,List.of(
            new DartFilingsResponse.Filing("00126380","20251114002447","20251114","분기보고서 (2025.09)")));
        when(gateway.quarterlyFilings("00126380",SamsungQ3CoverageService.FROM,through,1))
            .thenReturn(new ApiPayload<>(data,mapper.writeValueAsString(data)));
        var coverage=new SamsungQ3CoverageService(gateway,jdbc,transactions);
        coverage.collect(through);
        coverage.collect(through);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quarterly_filing_coverage",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_publication",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
        var incomplete=new DartFilingsResponse("000",1,1,2,List.of(
            new DartFilingsResponse.Filing("00126380","20251114002447","20251114","분기보고서 (2025.09)")));
        when(gateway.quarterlyFilings("00126380",SamsungQ3CoverageService.FROM,through,1))
            .thenReturn(new ApiPayload<>(incomplete,mapper.writeValueAsString(incomplete)));
        assertThatThrownBy(() -> coverage.collect(through)).isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
    }

    @Test void periodicCoverageKeepsExactQ3ReceiptAndNoIncompletePage() throws Exception {
        jdbc.update("UPDATE company SET corp_code='00126380' WHERE id=?",companyId);
        when(gateway.source()).thenReturn("OPENDART");
        var from=LocalDate.of(2025,9,30);
        var through=LocalDate.of(2025,12,31);
        var data=new DartFilingsResponse("000",1,1,1,List.of(
            new DartFilingsResponse.Filing("00126380","20251114002447","20251114","분기보고서 (2025.09)")));
        when(gateway.periodicFilings("00126380",io.corpscope.financial.ReportType.Q3,from,through,1))
            .thenReturn(new ApiPayload<>(data,mapper.writeValueAsString(data)));
        var coverage=new PeriodicFilingCoverageService(gateway,jdbc,transactions);
        coverage.collect("00126380",2025,io.corpscope.financial.ReportType.Q3,through);
        coverage.collect("00126380",2025,io.corpscope.financial.ReportType.Q3,through);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM periodic_filing_coverage",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_publication",Integer.class)).isEqualTo(1);
        var incomplete=new DartFilingsResponse("000",1,1,2,List.of(
            new DartFilingsResponse.Filing("00126380","20251114002447","20251114","분기보고서 (2025.09)")));
        when(gateway.periodicFilings("00126380",io.corpscope.financial.ReportType.Q3,from,through,1))
            .thenReturn(new ApiPayload<>(incomplete,mapper.writeValueAsString(incomplete)));
        assertThatThrownBy(() -> coverage.collect("00126380",2025,io.corpscope.financial.ReportType.Q3,through))
            .isInstanceOf(DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion",Integer.class)).isEqualTo(1);
    }

    private ApiPayload<DartFilingsResponse> response(int page,int pages,int count,String code,String title) throws Exception {
        var data = new DartFilingsResponse("000",page,pages,count,List.of(
                new DartFilingsResponse.Filing(code,"20250331000001","20250331",title)));
        return new ApiPayload<>(data,mapper.writeValueAsString(data));
    }
}
