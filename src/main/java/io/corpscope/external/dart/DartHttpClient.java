package io.corpscope.external.dart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.FinancialPeriod;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
@Profile("!demo")
public class DartHttpClient implements DartGateway {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final DartProperties properties;

    public DartHttpClient(RestClient dartRestClient, ObjectMapper mapper, DartProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        this.client = dartRestClient;
    }

    @Override
    public ApiPayload<DartCompanyResponse> company(String corpCode) {
        var response = get("/company.json", corpCode, null, DartCompanyResponse.class);
        checkStatus(response.data().status(), false);
        return response;
    }

    @Override
    public ApiPayload<DartFinancialResponse> financials(String corpCode, FinancialPeriod period) {
        var response = get("/fnlttSinglAcntAll.json", corpCode, period, DartFinancialResponse.class);
        checkStatus(response.data().status(), true);
        if ("000".equals(response.data().status())
                && (response.data().list() == null || response.data().list().isEmpty())) {
            throw new DartException("DART success response has no account rows");
        }
        return response;
    }

    @Override
    public ApiPayload<DartFilingsResponse> annualFilings(String corpCode, LocalDate end, int page) {
        if (corpCode == null || !corpCode.matches("[0-9]{8}") || page < 1 || page > 100
                || end == null || end.isBefore(LocalDate.of(2016, 1, 1))) {
            throw new IllegalArgumentException("Invalid filing search scope");
        }
        var response = request("/list.json", corpCode, Map.of("bgn_de", "20160101",
                "end_de", end.format(DateTimeFormatter.BASIC_ISO_DATE), "last_reprt_at", "N",
                "pblntf_detail_ty", "A001", "page_count", "100", "page_no", Integer.toString(page),
                "sort", "date", "sort_mth", "asc"), DartFilingsResponse.class);
        checkStatus(response.data().status(), true);
        return response;
    }

    @Override
    public ApiPayload<DartFilingsResponse> quarterlyFilings(String corpCode, LocalDate date, int page) {
        return quarterlyFilings(corpCode,date,date,page);
    }

    @Override
    public ApiPayload<DartFilingsResponse> quarterlyFilings(String corpCode, LocalDate from, LocalDate through, int page) {
        return periodicFilings(corpCode,io.corpscope.financial.ReportType.Q3,from,through,page);
    }

    @Override
    public ApiPayload<DartFilingsResponse> periodicFilings(String corpCode,
            io.corpscope.financial.ReportType reportType,LocalDate from,LocalDate through,int page) {
        if (corpCode == null || !corpCode.matches("[0-9]{8}") || page < 1 || page > 100
                || from == null || through == null || from.isBefore(LocalDate.of(2016, 1, 1))
                || through.isBefore(from) || through.isAfter(LocalDate.now())
                || java.time.temporal.ChronoUnit.DAYS.between(from,through)>550
                || reportType == null || reportType == io.corpscope.financial.ReportType.ANNUAL) {
            throw new IllegalArgumentException("Invalid quarterly filing search scope");
        }
        String detail=reportType==io.corpscope.financial.ReportType.HALF_YEAR ? "A002":"A003";
        var response = request("/list.json", corpCode, Map.of("bgn_de", from.format(DateTimeFormatter.BASIC_ISO_DATE),
                "end_de", through.format(DateTimeFormatter.BASIC_ISO_DATE), "last_reprt_at", "N", "pblntf_detail_ty", detail,
                "page_count", "100", "page_no", Integer.toString(page),
                "sort", "date", "sort_mth", "asc"), DartFilingsResponse.class);
        checkStatus(response.data().status(), true);
        return response;
    }

    @Override
    public byte[] document(String receiptNumber) {
        if (receiptNumber == null || !receiptNumber.matches("[0-9]{14}")) throw new IllegalArgumentException("Invalid receipt");
        if (properties.apiKey() == null || !properties.apiKey().matches("[a-zA-Z0-9]{40}")) {
            throw new DartException("Set a valid OPENDART_API_KEY before collecting real data");
        }
        try {
            return client.get().uri(b -> b.path("/document.xml").queryParam("crtfc_key", properties.apiKey())
                    .queryParam("rcept_no", receiptNumber).build()).exchange((request, response) -> {
                int code = response.getStatusCode().value();
                if (code == 429 || code >= 500) throw new DartException.Transient("DART HTTP " + code);
                if (code < 200 || code >= 300) throw new DartException("DART HTTP " + code);
                byte[] bytes = response.getBody().readNBytes(20 * 1024 * 1024 + 1);
                if (bytes.length > 20 * 1024 * 1024 || bytes.length < 4
                        || bytes[0] != 'P' || bytes[1] != 'K') {
                    throw new DartException("DART document is not a bounded ZIP response; check API status");
                }
                return bytes;
            });
        } catch (ResourceAccessException ex) {
            throw new DartException.Transient("DART connection or read timeout");
        } catch (RestClientException ex) { throw new DartException("DART document transfer failed"); }
    }

    private <T> ApiPayload<T> get(String path, String corpCode, FinancialPeriod period, Class<T> type) {
        Map<String, String> params = period == null ? Map.of() : Map.of(
                "bsns_year", Integer.toString(period.fiscalYear()), "reprt_code", period.reportType().dartCode(),
                "fs_div", period.statementType().name());
        return request(path, corpCode, params, type);
    }

    private <T> ApiPayload<T> request(String path, String corpCode, Map<String, String> params, Class<T> type) {
        if (properties.apiKey() == null || !properties.apiKey().matches("[a-zA-Z0-9]{40}")) {
            throw new DartException("Set a valid OPENDART_API_KEY before collecting real data");
        }
        try {
            String json = client.get().uri(builder -> {
                builder.path(path).queryParam("crtfc_key", properties.apiKey()).queryParam("corp_code", corpCode);
                params.forEach(builder::queryParam);
                return builder.build();
            }).retrieve().body(String.class);
            if (json == null || json.isBlank()) throw new DartException("Empty DART response");
            T data = mapper.readValue(json, type);
            if (data == null) throw new DartException("Null DART response");
            return new ApiPayload<>(data, json);
        } catch (ResourceAccessException ex) {
            // Do not retain the original exception: its URI can contain crtfc_key.
            throw new DartException.Transient("DART connection or read timeout");
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 429 || status >= 500) throw new DartException.Transient("DART HTTP " + status);
            throw new DartException("DART HTTP " + status);
        } catch (JsonProcessingException | RestClientException ex) {
            throw new DartException("Malformed DART response");
        }
    }

    static void checkStatus(String status, boolean allowNoData) {
        if ("000".equals(status) || (allowNoData && "013".equals(status))) return;
        if ("800".equals(status) || "900".equals(status)) {
            throw new DartException.Transient("DART status " + status);
        }
        // Quota exhaustion and invalid keys must stop the job rather than amplify requests.
        throw new DartException("DART rejected request (status "
                + (status != null && status.matches("[0-9]{3}") ? status : "invalid") + ")");
    }
}
