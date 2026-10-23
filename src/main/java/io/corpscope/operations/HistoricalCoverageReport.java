package io.corpscope.operations;

import io.corpscope.financial.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record HistoricalCoverageReport(LocalDate asOf,Instant generatedAt,long totalScopes,
        int returnedScopes,boolean truncated,Map<String,Long> returnedStatusCounts,
        List<Scope> scopes,boolean forecastReady,List<String> limitations) {
    public record Scope(long companyId,String corpCode,String companyName,int fiscalYear,
        ReportType reportType,StatementType statementType,String availabilityStatus,
        String receiptNumber,LocalDate receiptDate,LocalDate availableFromKstDate,
        Boolean analysisAllowed,String exclusionReason,List<FilingCandidate> filingCandidates,
        boolean candidatesTruncated,List<String> nextActions) {}
    public record FilingCandidate(String receiptNumber,LocalDate receiptDate,String reportTitle,
        boolean correctionMarked,boolean documentStored,boolean sixTotalsReviewed) {}
}
