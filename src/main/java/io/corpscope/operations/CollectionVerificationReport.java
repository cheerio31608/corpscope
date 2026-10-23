package io.corpscope.operations;

import io.corpscope.analysis.CompanyFinancialAnalysisResponse;
import io.corpscope.financial.FinancialPeriod;
import java.util.List;
import java.util.Map;

public record CollectionVerificationReport(String status, String corpCode, Long companyId, FinancialPeriod period,
        long financialRows, boolean rawMatchesStoredFacts, int unmappedRows, int missingAmountRows,
        List<String> currencies, List<String> sources, Map<String, Long> standardAccountCounts,
        List<String> unmappedNameSamples, List<String> warnings, List<String> errors,
        CompanyFinancialAnalysisResponse analysis) {}
