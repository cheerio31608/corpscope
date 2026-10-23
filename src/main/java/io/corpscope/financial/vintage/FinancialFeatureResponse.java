package io.corpscope.financial.vintage;

import io.corpscope.analysis.FinancialMetricsCalculator;
import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Numeric observations and their evidence. Collection/review times are deliberately not features. */
public record FinancialFeatureResponse(String schemaVersion,long companyId,String corpCode,
        int fiscalYear,ReportType reportType,StatementType statementType,LocalDate asOf,
        Evidence evidence,Map<StandardAccount,BigDecimal> values,ComparisonEvidence comparisonEvidence,FinancialMetricsCalculator.Metrics metrics,
        boolean analysisAllowed,String exclusionReason,Map<String,String> unavailableMetricReasons,
        boolean forecastReady,List<String> warnings) {
    public record Evidence(String receiptNumber,LocalDate receiptDate,LocalDate availableFromKstDate,
            String documentSha256,String extractionProfile,String source,String currency) {}
    public record ComparisonEvidence(String comparisonProfile,LocalDate evidenceAvailableFromKstDate,
            int previousFiscalYear,Evidence previous,Map<StandardAccount,BigDecimal> previousValues) {}
}
