package io.corpscope.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.external.dart.*;
import io.corpscope.financial.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class DartTransformerTest {
    private final DartTransformer transformer = new DartTransformer(new AccountStandardizer());
    private final FinancialPeriod period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);

    @Test void transformsExternalFixtureAndKeepsOriginalLabel() throws IOException {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var raw = mapper.readValue(getClass().getResourceAsStream("/demo/financials-2024.json"), DartFinancialResponse.class);
        var fact = transformer.financial(raw.list().getFirst(), "00000001", period);
        assertThat(fact.amount()).isEqualByComparingTo("1200");
        assertThat(fact.rawAccountName()).isEqualTo("매출액");
        assertThat(fact.standardAccount()).isEqualTo(StandardAccount.REVENUE);
        assertThat(fact.accountKey()).hasSize(64);
        var company = mapper.readValue(getClass().getResourceAsStream("/demo/company.json"), DartCompanyResponse.class);
        assertThat(transformer.company(company, "00000001").establishedDate()).hasToString("2020-01-01");
        assertThat(transformer.company(company, "00000001").stockCode()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"매출", "매출액", "영업수익", "Revenue"})
    void mapsAliases(String name) {
        assertThat(new AccountStandardizer().map("-표준계정코드 미사용-", name, "IS")).isEqualTo(StandardAccount.REVENUE);
    }

    @Test void isolatesCashflowAndUnknownAccounts() {
        var mapper = new AccountStandardizer();
        assertThat(mapper.map("ifrs-full_ProfitLoss", "당기순이익", "CF")).isEqualTo(StandardAccount.UNMAPPED);
        assertThat(mapper.map("custom-code", "모르는 계정", "IS")).isEqualTo(StandardAccount.UNMAPPED);
    }

    @ParameterizedTest @ValueSource(strings = {"IS", "CIS"})
    void mapsLegacyRevenueTotalAndPreservesItsIdentity(String division) {
        var raw = new DartFinancialResponse.Account("00000001", "2024", "11011", "20250401000001", division,
                "ifrs_Revenue", "수익(매출액)", "-", "59,814,862", "", "KRW", "1");
        var fact = transformer.financial(raw, "00000001", period);
        assertThat(fact.standardAccount()).isEqualTo(StandardAccount.REVENUE);
        assertThat(fact.accountCode()).isEqualTo("ifrs_Revenue");
        assertThat(fact.rawAccountName()).isEqualTo("수익(매출액)");
        assertThat(fact.accountKey()).isEqualTo(DartTransformer.sha256("12:ifrs_Revenue:-"));
        assertThat(fact.amount()).isEqualByComparingTo("59814862");
    }

    @Test void legacyRevenueDoesNotBroadenToComponentsOrOtherStatements() {
        var standardizer = new AccountStandardizer();
        for (String code : java.util.List.of("ifrs_RevenueFromSaleOfGoods", "ifrs_RevenueFromRenderingOfServices",
                "ifrs_RevenueFromConstructionContracts", "ifrs_OtherRevenue")) {
            assertThat(standardizer.map(code, "수익(매출액)", "CIS")).as(code)
                    .isEqualTo(StandardAccount.UNMAPPED);
        }
        for (String division : java.util.List.of("BS", "CF", "SCE")) {
            assertThat(standardizer.map("ifrs_Revenue", "수익(매출액)", division))
                    .isEqualTo(StandardAccount.UNMAPPED);
        }
    }

    @Test void legacyEquityTotalIsNotParentEquityOrEquityAndLiabilities() {
        var standardizer = new AccountStandardizer();
        assertThat(standardizer.map("ifrs_Equity", "당기말자본", "BS")).isEqualTo(StandardAccount.EQUITY);
        for (String code : java.util.List.of("ifrs_EquityAttributableToOwnersOfParent", "ifrs_EquityAndLiabilities")) {
            assertThat(standardizer.map(code, "기타", "BS")).isEqualTo(StandardAccount.UNMAPPED);
        }
        assertThat(standardizer.map("ifrs_Equity", "당기말자본", "SCE")).isEqualTo(StandardAccount.UNMAPPED);
    }

    @Test void mapsConsolidatedNetIncomeWithoutChangingOriginalIdentityOrAmount() {
        var raw = new DartFinancialResponse.Account("00000001", "2024", "11011", "20250401000001", "IS",
                "-표준계정코드 미사용-", "연결당기순이익", "-", "-1,234", "", "KRW", "20");
        var fact = transformer.financials(java.util.List.of(raw), "00000001", period).getFirst();
        assertThat(fact.standardAccount()).isEqualTo(StandardAccount.NET_INCOME);
        assertThat(fact.rawAccountName()).isEqualTo("연결당기순이익");
        assertThat(fact.accountCode()).isEqualTo("-표준계정코드 미사용-");
        assertThat(fact.accountKey()).isEqualTo(DartTransformer.sha256("7:연결당기순이익:-"));
        assertThat(fact.amount()).isEqualByComparingTo("-1234");
    }

    @Test void doesNotTreatNetIncomeComponentsOrOtherStatementsAsTheTotal() {
        var standardizer = new AccountStandardizer();
        for (String label : java.util.List.of("계속영업연결당기순이익", "중단영업당기순이익(손실)",
                "지배기업 소유주 연결당기순이익", "연결당기순이익 주당금액")) {
            assertThat(standardizer.map("-표준계정코드 미사용-", label, "IS"))
                    .as(label).isEqualTo(StandardAccount.UNMAPPED);
        }
        for (String division : java.util.List.of("BS", "CF", "SCE")) {
            assertThat(standardizer.map("-표준계정코드 미사용-", "연결당기순이익", division))
                    .as(division).isEqualTo(StandardAccount.UNMAPPED);
        }
        for (String exact : java.util.List.of("연결분기순이익", "연결반기순이익")) {
            assertThat(standardizer.map("-표준계정코드 미사용-", exact, "IS"))
                .isEqualTo(StandardAccount.NET_INCOME);
            assertThat(standardizer.map("-표준계정코드 미사용-", exact, "CF"))
                .isEqualTo(StandardAccount.UNMAPPED);
            assertThat(standardizer.map("-표준계정코드 미사용-", exact+" 주당금액", "IS"))
                .isEqualTo(StandardAccount.UNMAPPED);
        }
        assertThat(standardizer.map("-표준계정코드 미사용-", "분기순이익(손실)", "CIS"))
            .isEqualTo(StandardAccount.NET_INCOME);
        assertThat(standardizer.map("-표준계정코드 미사용-", "분기순이익(손실)", "CF"))
            .isEqualTo(StandardAccount.UNMAPPED);
    }

    @Test void differentiatesNonstandardAccountsAndSceDimensions() {
        var first = transformer.financial(row("비표준1", ""), "00000001", period);
        var second = transformer.financial(row("비표준2", ""), "00000001", period);
        var dimension = transformer.financial(row("비표준1", "지배기업 소유주"), "00000001", period);
        assertThat(first.accountKey()).isNotEqualTo(second.accountKey()).isNotEqualTo(dimension.accountKey());
    }

    @Test void preservesMissingAndNegativeAmountsAndRejectsGarbage() {
        assertThat(DartTransformer.amount("")).isNull();
        assertThat(DartTransformer.amount("-")).isNull();
        assertThat(DartTransformer.amount("(1,234)")).isEqualByComparingTo("-1234");
        assertThatThrownBy(() -> DartTransformer.amount("금액오류")).isInstanceOf(DataQualityException.class);
        assertThatThrownBy(() -> DartTransformer.amount("1.234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transformer.financial(row("매출", ""), "00000002", period))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DartFinancialResponse.Account row(String name, String detail) {
        return new DartFinancialResponse.Account("00000001", "2024", "11011", "20250401000001", "SCE",
                "-표준계정코드 미사용-", name, detail, "1", "", "KRW", null);
    }
}
