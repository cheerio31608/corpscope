package io.corpscope.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.external.dart.*;
import io.corpscope.financial.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DartDataQualityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DartTransformer transformer = new DartTransformer(new AccountStandardizer());
    private final FinancialPeriod halfYear = new FinancialPeriod(2024, ReportType.HALF_YEAR, StatementType.CFS);

    @Test void quarterlyFixturePreservesCurrentAndCumulativeAmountsAndSceDimensions() throws Exception {
        var raw = fixture("financials-half-year.json", DartFinancialResponse.class);
        var rows = raw.list().stream().map(row -> transformer.financial(row, "00000003", halfYear)).toList();
        assertThat(rows.getFirst().accountCode()).isNull();
        assertThat(rows.getFirst().standardAccount()).isEqualTo(StandardAccount.REVENUE);
        assertThat(rows.getFirst().rawAccountName()).isEqualTo("Revenue");
        assertThat(rows.getFirst().amount()).isEqualByComparingTo("12340");
        assertThat(rows.getFirst().cumulativeAmount()).isEqualByComparingTo("34560");
        assertThat(rows.get(2).amount()).isEqualByComparingTo("-123");
        assertThat(rows.get(2).cumulativeAmount()).isEqualByComparingTo("-456");
        assertThat(rows.get(3).amount()).isNull();
        assertThat(rows.get(3).standardAccount()).isEqualTo(StandardAccount.UNMAPPED);
        assertThat(rows.get(4).amount()).isNull();
        assertThat(rows.get(5).accountKey()).isNotEqualTo(rows.get(6).accountKey());
        assertThat(rows.get(5).standardAccount()).isEqualTo(StandardAccount.UNMAPPED);
        var company = transformer.company(fixture("company-edge.json", DartCompanyResponse.class), "00000003");
        assertThat(company.stockCode()).isEqualTo("000003");
        assertThat(company.corporateRegistrationNumber()).isEqualTo("0000000000003");
        assertThat(company.ceo()).isNull();
        assertThat(company.establishedDate()).hasToString("2020-02-29");
    }

    @Test void duplicateStandardIdsFailEvenWhenLabelsDiffer() throws Exception {
        DartGateway gateway = mock(DartGateway.class);
        var company = payload("company-edge.json", DartCompanyResponse.class);
        var financial = payload("financials-duplicate.json", DartFinancialResponse.class);
        when(gateway.company("00000003")).thenReturn(company);
        when(gateway.financials("00000003", halfYear)).thenReturn(financial);
        assertThatThrownBy(() -> new CompanyCollector(gateway, transformer).collect("00000003", halfYear))
                .isInstanceOfSatisfying(DataQualityException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DataQualityException.Code.DUPLICATE_ACCOUNT));
    }

    @Test void sameAccountInDifferentStatementDivisionsIsNotAStorageDuplicate() throws Exception {
        DartGateway gateway = mock(DartGateway.class);
        var company = payload("company-edge.json", DartCompanyResponse.class);
        var financial = payload("financials-half-year.json", DartFinancialResponse.class);
        when(gateway.company("00000003")).thenReturn(company);
        when(gateway.financials("00000003", halfYear)).thenReturn(financial);
        when(gateway.source()).thenReturn("DEMO");
        assertThat(new CompanyCollector(gateway, transformer).collect("00000003", halfYear).financials()).hasSize(8);
    }

    @ParameterizedTest @MethodSource("validAmounts")
    void acceptsDefinedNumericForms(String input, String expected) {
        if (expected == null) assertThat(DartTransformer.amount(input)).isNull();
        else assertThat(DartTransformer.amount(input)).isEqualByComparingTo(expected);
    }

    static Stream<Arguments> validAmounts() {
        return Stream.of(Arguments.of(null, null), Arguments.of(" ", null), Arguments.of("-", null),
                Arguments.of("0", "0"), Arguments.of("-0", "0"), Arguments.of(" 1,234.50 ", "1234.50"),
                Arguments.of("(1,234.50)", "-1234.50"), Arguments.of("+123", "123"),
                Arguments.of("999999999999999999999999999999999999.99", "999999999999999999999999999999999999.99"));
    }

    @ParameterizedTest @ValueSource(strings = {"1,23", "12,34,567", "1,,000", "1e3", "NaN", "(-10)", "(+10)", "1 000", "1,000원"})
    void malformedAmountsFailWithoutEchoingInput(String input) {
        assertThatThrownBy(() -> DartTransformer.amount(input)).isInstanceOfSatisfying(DataQualityException.class,
                ex -> assertThat(ex.code()).isEqualTo(DataQualityException.Code.AMOUNT_FORMAT))
                .hasMessageNotContaining(input);
    }

    @ParameterizedTest @ValueSource(strings = {"0.001", "1000000000000000000000000000000000000"})
    void rejectsValuesThatWouldBeRoundedOrOverflowInPostgres(String input) {
        assertThatThrownBy(() -> DartTransformer.amount(input)).isInstanceOfSatisfying(DataQualityException.class,
                ex -> assertThat(ex.code()).isEqualTo(DataQualityException.Code.AMOUNT_RANGE));
    }

    @ParameterizedTest @ValueSource(strings = {"20230229", "00000000", "2024-01-01"})
    void invalidCompanyDatesAreExplicitQualityFailures(String input) throws Exception {
        var original = fixture("company-edge.json", DartCompanyResponse.class);
        var raw = new DartCompanyResponse(original.status(), original.message(), original.corpCode(), original.corpName(),
                original.stockCode(), original.corporateRegistrationNumber(), original.businessRegistrationNumber(),
                original.ceo(), original.industryCode(), input, original.address(), original.homepage());
        assertThatThrownBy(() -> transformer.company(raw, "00000003"))
                .isInstanceOfSatisfying(DataQualityException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DataQualityException.Code.COMPANY_DATE));
    }

    @Test void missingCurrencyIsRejectedRatherThanAssumedKrw() throws Exception {
        var row = fixture("financials-half-year.json", DartFinancialResponse.class).list().getFirst();
        var invalid = new DartFinancialResponse.Account(row.corpCode(), row.fiscalYear(), row.reportCode(), row.receiptNumber(),
                row.statementDivision(), row.accountCode(), row.accountName(), row.accountDetail(), row.amount(),
                row.cumulativeAmount(), null, row.ordinal());
        assertThatThrownBy(() -> transformer.financial(invalid, "00000003", halfYear))
                .isInstanceOfSatisfying(DataQualityException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DataQualityException.Code.FINANCIAL_IDENTITY));
    }

    private <T> T fixture(String file, Class<T> type) throws Exception { return payload(file, type).data(); }
    private <T> ApiPayload<T> payload(String file, Class<T> type) throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + file)) {
            String json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return new ApiPayload<>(mapper.readValue(json, type), json);
        }
    }
}
