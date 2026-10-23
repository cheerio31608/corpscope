package io.corpscope.external.dart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Deliberately fictional data for reproducible runs without credentials or network. */
@Component
@Profile("demo")
public class DemoDartClient implements DartGateway {
    private final ObjectMapper mapper;
    public DemoDartClient(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public String source() { return "DEMO"; }

    @Override public ApiPayload<DartCompanyResponse> company(String corpCode) {
        requireCode(corpCode);
        return load("demo/company.json", DartCompanyResponse.class);
    }

    @Override public ApiPayload<DartFinancialResponse> financials(String corpCode, FinancialPeriod period) {
        requireCode(corpCode);
        if (period.reportType() != ReportType.ANNUAL || period.statementType() != StatementType.CFS
                || (period.fiscalYear() != 2023 && period.fiscalYear() != 2024)) {
            throw new IllegalArgumentException("Demo supports only 2023/2024 ANNUAL CFS");
        }
        return load("demo/financials-" + period.fiscalYear() + ".json", DartFinancialResponse.class);
    }

    private void requireCode(String code) {
        if (!"00000001".equals(code)) throw new IllegalArgumentException("Demo corpCode is 00000001");
    }

    private <T> ApiPayload<T> load(String path, Class<T> type) {
        try {
            String raw = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            return new ApiPayload<>(mapper.readValue(raw, type), raw);
        } catch (IOException ex) { throw new IllegalStateException("Cannot load demo fixture", ex); }
    }
}
