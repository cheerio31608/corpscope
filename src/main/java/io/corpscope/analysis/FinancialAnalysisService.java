package io.corpscope.analysis;

import io.corpscope.common.NotFoundException;
import io.corpscope.company.CompanyRepository;
import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FinancialAnalysisService {
    private final CompanyRepository companies;
    private final FinancialFactRepository financials;
    private final FinancialMetricsCalculator calculator;
    private final AnalysisEligibilityProperties eligibility;

    public FinancialAnalysisService(CompanyRepository companies, FinancialFactRepository financials,
                                    FinancialMetricsCalculator calculator, AnalysisEligibilityProperties eligibility) {
        this.companies = companies;
        this.financials = financials;
        this.calculator = calculator;
        this.eligibility = eligibility;
    }

    public CompanyFinancialAnalysisResponse analyze(Long id, FinancialPeriod period) {
        if (period.reportType() != ReportType.ANNUAL) {
            throw new IllegalArgumentException("Analysis currently supports ANNUAL reports only");
        }
        var company = companies.findById(id).orElseThrow(() -> new NotFoundException("Company not found: " + id));
        var current = financials.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(
                id, period.fiscalYear(), period.reportType(), period.statementType());
        if (current.isEmpty()) throw new NotFoundException("Financial period not collected");
        String exclusionReason = company.getCorpCode() == null ? null
                : eligibility.excludedCompanyReasons().get(company.getCorpCode());
        String exclusionPrefix = "ANALYSIS_EXCLUDED: ";
        if (exclusionReason == null && company.getCorpCode() != null) {
            String key = company.getCorpCode() + ":" + period.fiscalYear() + ":"
                    + period.reportType().name() + ":" + period.statementType().name();
            exclusionReason = eligibility.excludedPeriodReasons().get(key);
            exclusionPrefix = "PERIOD_ANALYSIS_EXCLUDED: ";
        }
        if (exclusionReason != null) {
            var currencies = current.stream().map(FinancialFact::getCurrency).distinct().toList();
            return new CompanyFinancialAnalysisResponse(
                    new CompanyFinancialAnalysisResponse.CompanySummary(id, company.getCorpCode(), company.getName()),
                    period, currencies.size() == 1 ? currencies.getFirst() : null,
                    null, null, null, null, null, List.of(exclusionPrefix + exclusionReason));
        }
        var previous = financials.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(
                id, period.fiscalYear() - 1, period.reportType(), period.statementType());
        List<String> warnings = new ArrayList<>();
        Set<String> currencies = new HashSet<>();
        java.util.stream.Stream.concat(current.stream(), previous.stream())
                .filter(f -> f.getStandardAccount() != StandardAccount.UNMAPPED)
                .map(FinancialFact::getCurrency).forEach(currencies::add);
        boolean currencyValid = currencies.size() == 1;
        if (!currencyValid) warnings.add("Missing or mixed currencies: calculations suppressed");
        var now = inputs(currencyValid ? current : List.of(), "current", warnings);
        var before = inputs(currencyValid ? previous : List.of(), "previous", warnings);
        var metrics = calculator.calculate(now, before);
        if (metrics.revenueGrowth() == null) warnings.add("Revenue growth unavailable: missing or nonpositive prior revenue");
        if (metrics.operatingMargin() == null) warnings.add("Operating margin unavailable: missing values or nonpositive revenue");
        if (metrics.debtRatio() == null) warnings.add("Debt ratio unavailable: missing values or nonpositive equity");
        if (metrics.roa() == null) warnings.add("ROA unavailable: missing profit or positive beginning/ending assets");
        if (metrics.roe() == null) warnings.add("ROE unavailable: missing profit or positive beginning/ending equity");
        return new CompanyFinancialAnalysisResponse(
                new CompanyFinancialAnalysisResponse.CompanySummary(id, company.getCorpCode(), company.getName()),
                period, currencyValid ? currencies.iterator().next() : null,
                metrics.revenueGrowth(), metrics.operatingMargin(), metrics.debtRatio(), metrics.roa(), metrics.roe(),
                List.copyOf(warnings));
    }

    private FinancialMetricsCalculator.Inputs inputs(List<FinancialFact> rows, String label, List<String> warnings) {
        return new FinancialMetricsCalculator.Inputs(value(rows, StandardAccount.REVENUE, label, warnings),
                value(rows, StandardAccount.OPERATING_PROFIT, label, warnings),
                value(rows, StandardAccount.NET_INCOME, label, warnings),
                value(rows, StandardAccount.ASSETS, label, warnings),
                value(rows, StandardAccount.LIABILITIES, label, warnings),
                value(rows, StandardAccount.EQUITY, label, warnings));
    }

    private BigDecimal value(List<FinancialFact> rows, StandardAccount account, String period, List<String> warnings) {
        // OpenDART uses '-' for undimensioned totals; retain the original detail in storage.
        var candidates = rows.stream().filter(f -> f.getStandardAccount() == account
                        && (f.getAccountDetail().isBlank() || "-".equals(f.getAccountDetail())))
                .filter(f -> switch (account) {
                    case ASSETS, LIABILITIES, EQUITY -> "BS".equals(f.getStatementDivision());
                    default -> "IS".equals(f.getStatementDivision()) || "CIS".equals(f.getStatementDivision());
                }).toList();
        // IS is preferred when the same income total is also exposed in CIS.
        var income = candidates.stream().filter(f -> "IS".equals(f.getStatementDivision())).toList();
        if (!income.isEmpty()) candidates = income;
        if (candidates.size() != 1) {
            warnings.add(period + " " + account + ": missing or ambiguous standard account");
            return null;
        }
        return candidates.getFirst().getAmount();
    }
}
