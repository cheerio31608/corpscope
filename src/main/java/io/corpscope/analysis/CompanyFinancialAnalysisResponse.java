package io.corpscope.analysis;

import io.corpscope.financial.FinancialPeriod;
import java.math.BigDecimal;
import java.util.List;

public record CompanyFinancialAnalysisResponse(CompanySummary company, FinancialPeriod period, String currency,
        BigDecimal revenueGrowth, BigDecimal operatingMargin, BigDecimal debtRatio, BigDecimal roa,
        BigDecimal roe, List<String> warnings) {
    public record CompanySummary(Long id, String corpCode, String name) {}
}
