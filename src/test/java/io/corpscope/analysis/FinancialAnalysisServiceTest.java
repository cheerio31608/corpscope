package io.corpscope.analysis;

import io.corpscope.company.*;
import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialAnalysisServiceTest {
    @ParameterizedTest
    @CsvSource({"00159193,2022,CFS,true", "00159193,2023,CFS,false",
            "00159193,2022,OFS,false", "00261285,2022,CFS,false"})
    void periodReviewAppliesOnlyToTheExactCompanyYearAndStatementScope(
            String corpCode, int year, StatementType type, boolean excluded) {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var company = mock(Company.class);
        when(company.getCorpCode()).thenReturn(corpCode);
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        var current = List.of(revenue("KRW"));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, year, ReportType.ANNUAL, type))
                .thenReturn(current);
        if (!excluded) {
            var previous = List.of(revenue("KRW"));
            when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, year - 1, ReportType.ANNUAL, type))
                    .thenReturn(previous);
        }
        var calculator = spy(new FinancialMetricsCalculator());
        var policy = new AnalysisEligibilityProperties(Map.of(),
                Map.of("00159193:2022:ANNUAL:CFS", "Prior comparisons were restated"));
        var result = new FinancialAnalysisService(companies, repo, calculator, policy)
                .analyze(1L, new FinancialPeriod(year, ReportType.ANNUAL, type));
        if (excluded) {
            assertThat(result.revenueGrowth()).isNull();
            assertThat(result.operatingMargin()).isNull();
            assertThat(result.debtRatio()).isNull();
            assertThat(result.roa()).isNull();
            assertThat(result.roe()).isNull();
            assertThat(result.warnings()).containsExactly("PERIOD_ANALYSIS_EXCLUDED: Prior comparisons were restated");
            verifyNoInteractions(calculator);
        } else {
            assertThat(result.revenueGrowth()).isEqualByComparingTo("0");
            assertThat(result.warnings()).noneMatch(warning -> warning.contains("ANALYSIS_EXCLUDED"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"00159193", "00159193:2014:ANNUAL:CFS",
            "00159193:2022:Q1:CFS", "00159193:2022:ANNUAL:ALL"})
    void rejectsInvalidPeriodReviewScopes(String key) {
        assertThatThrownBy(() -> new AnalysisEligibilityProperties(Map.of(), Map.of(key, "Review")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00877059", "00635134"})
    void excludedCompanyKeepsIdentityAndCurrencyButDoesNotCalculateMetrics(String corpCode) {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var calculator = mock(FinancialMetricsCalculator.class);
        var company = mock(Company.class);
        when(company.getCorpCode()).thenReturn(corpCode);
        when(company.getName()).thenReturn("비교 기준 검토 기업");
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        var current = List.of(revenue("KRW"));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2025, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(current);
        var result = new FinancialAnalysisService(companies, repo, calculator,
                new AnalysisEligibilityProperties(Map.of(corpCode, "Comparative basis requires review"), Map.of()))
                .analyze(1L, new FinancialPeriod(2025, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.company().corpCode()).isEqualTo(corpCode);
        assertThat(result.currency()).isEqualTo("KRW");
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.operatingMargin()).isNull();
        assertThat(result.debtRatio()).isNull();
        assertThat(result.roa()).isNull();
        assertThat(result.roe()).isNull();
        assertThat(result.warnings()).containsExactly("ANALYSIS_EXCLUDED: Comparative basis requires review");
        verifyNoInteractions(calculator);
        verify(repo, never()).findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS);
    }

    @Test void exclusionsUseExactCorpCodeAndDoNotAffectOtherCompanies() {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var company = mock(Company.class);
        when(company.getCorpCode()).thenReturn("00126380");
        when(companies.findById(1L)).thenReturn(Optional.of(company));
        var current = List.of(revenue("KRW"));
        var previous = List.of(revenue("KRW"));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2025, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(current);
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(previous);
        var result = new FinancialAnalysisService(companies, repo, new FinancialMetricsCalculator(),
                new AnalysisEligibilityProperties(Map.of("00877059", "Review"), Map.of()))
                .analyze(1L, new FinancialPeriod(2025, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.revenueGrowth()).isEqualByComparingTo("0");
        assertThat(result.warnings()).noneMatch(warning -> warning.startsWith("ANALYSIS_EXCLUDED"));
    }

    @Test void dartPlaceholderTotalsAreUsedButDimensionedRevenueIsExcluded() {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var total = revenue("KRW");
        when(total.getAccountDetail()).thenReturn("-");
        when(total.getAmount()).thenReturn(new BigDecimal("120"));
        var dimensioned = revenue("KRW");
        when(dimensioned.getAccountDetail()).thenReturn("사업부문 [구성요소]");
        var previous = List.of(revenue("KRW"));
        when(companies.findById(1L)).thenReturn(Optional.of(mock(Company.class)));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(List.of(total, dimensioned));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2023, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(previous);
        var result = new FinancialAnalysisService(companies, repo, new FinancialMetricsCalculator(), new AnalysisEligibilityProperties(Map.of(), Map.of()))
                .analyze(1L, new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.revenueGrowth()).isEqualByComparingTo("20.0000");
        assertThat(result.warnings()).noneMatch(message -> message.startsWith("current REVENUE:"));
    }

    @Test void blankAndPlaceholderTotalsRemainAmbiguousRatherThanSummed() {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var placeholder = revenue("KRW");
        when(placeholder.getAccountDetail()).thenReturn("-");
        var current = List.of(revenue("KRW"), placeholder);
        when(companies.findById(1L)).thenReturn(Optional.of(mock(Company.class)));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(current);
        var result = new FinancialAnalysisService(companies, repo, new FinancialMetricsCalculator(), new AnalysisEligibilityProperties(Map.of(), Map.of()))
                .analyze(1L, new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.warnings()).anyMatch(message -> message.startsWith("current REVENUE:"));
    }

    @Test void ambiguousAccountsAreNotSilentlySummed() {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var current = List.of(revenue("KRW"), revenue("KRW"));
        var previous = List.of(revenue("KRW"));
        when(companies.findById(1L)).thenReturn(Optional.of(mock(Company.class)));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(current);
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2023, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(previous);
        var service = new FinancialAnalysisService(companies, repo, new FinancialMetricsCalculator(), new AnalysisEligibilityProperties(Map.of(), Map.of()));
        var result = service.analyze(1L, new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.warnings()).anyMatch(message -> message.contains("ambiguous"));
    }

    @Test void mixedCurrenciesSuppressMetrics() {
        var companies = mock(CompanyRepository.class);
        var repo = mock(FinancialFactRepository.class);
        var current = List.of(revenue("USD"));
        var previous = List.of(revenue("KRW"));
        when(companies.findById(1L)).thenReturn(Optional.of(mock(Company.class)));
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2024, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(current);
        when(repo.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(1L, 2023, ReportType.ANNUAL, StatementType.CFS))
                .thenReturn(previous);
        var result = new FinancialAnalysisService(companies, repo, new FinancialMetricsCalculator(), new AnalysisEligibilityProperties(Map.of(), Map.of()))
                .analyze(1L, new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.currency()).isNull();
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.warnings()).anyMatch(message -> message.contains("currencies"));
    }

    private FinancialFact revenue(String currency) {
        var f = mock(FinancialFact.class);
        when(f.getStandardAccount()).thenReturn(StandardAccount.REVENUE);
        when(f.getCurrency()).thenReturn(currency);
        lenient().when(f.getAccountDetail()).thenReturn("");
        lenient().when(f.getStatementDivision()).thenReturn("IS");
        lenient().when(f.getAmount()).thenReturn(new BigDecimal("100"));
        return f;
    }
}
