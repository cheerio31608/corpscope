package io.corpscope.dataset;

import io.corpscope.operations.*;
import io.corpscope.financial.*;
import io.corpscope.financial.vintage.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class DatasetReadinessService {
    public record Period(String corpCode,String company,int fiscalYear,StatementType statement,
        String status,String reason,LocalDate financialAvailableFrom,String receipt,
        List<String> missingMetrics,List<String> warnings) {}
    public record CompanySummary(String corpCode,String company,List<Integer> fullMetricYears,
        List<Integer> partialMetricYears,int withheldScopes) {}
    public record Report(LocalDate asOf,int requestedScopes,int returnedScopes,boolean truncated,
        Map<String,Long> statusCounts,List<CompanySummary> companies,List<Period> periods,
        int independentFullMetricPeriods,int verifiedPriceRows,int realLabeledSamples,
        String priceStatus,String targetPolicy,boolean forecastReady,List<String> limitations) {}
    private final HistoricalCoverageService coverage;
    private final FinancialFeatureService features;
    public DatasetReadinessService(HistoricalCoverageService coverage,FinancialFeatureService features) {
        this.coverage=coverage;this.features=features;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report review(LocalDate asOf) {
        var inventory=coverage.review(asOf,500,"");
        List<Period> periods=new ArrayList<>();
        for(var scope:inventory.scopes()) {
            String status="WITHHELD",reason=scope.availabilityStatus();
            List<String> missing=List.of(),warnings=List.of();
            if("REVIEWED_VALUES_AVAILABLE".equals(scope.availabilityStatus())) {
                var row=features.create(scope.companyId(),scope.fiscalYear(),scope.reportType(),scope.statementType(),asOf);
                missing=List.copyOf(row.unavailableMetricReasons().keySet().stream().sorted().toList());
                warnings=row.warnings();
                if(!row.analysisAllowed()) reason="ANALYSIS_EXCLUDED: "+row.exclusionReason();
                else if(scope.statementType()!=StatementType.CFS) reason="OUTSIDE_CFS_PILOT";
                else { status=missing.isEmpty() ? "FULL_FINANCIAL_CANDIDATE":"PARTIAL_FINANCIAL_CANDIDATE";reason=null; }
            } else if(scope.exclusionReason()!=null) reason+="; POLICY: "+scope.exclusionReason();
            periods.add(new Period(scope.corpCode(),scope.companyName(),scope.fiscalYear(),scope.statementType(),status,reason,
                scope.availableFromKstDate(),scope.receiptNumber(),missing,warnings));
        }
        List<CompanySummary> companies=new ArrayList<>();
        var groups=new TreeMap<String,List<Period>>();
        periods.forEach(p -> groups.computeIfAbsent(p.corpCode(),k -> new ArrayList<>()).add(p));
        groups.forEach((code,ps) -> companies.add(new CompanySummary(code,ps.getFirst().company(),
            years(ps,"FULL_FINANCIAL_CANDIDATE"),years(ps,"PARTIAL_FINANCIAL_CANDIDATE"),
            (int)ps.stream().filter(p -> p.status().equals("WITHHELD")).count())));
        var counts=new TreeMap<String,Long>();periods.forEach(p -> counts.merge(p.status(),1L,Long::sum));
        return new Report(asOf,(int)inventory.totalScopes(),periods.size(),inventory.truncated(),Map.copyOf(counts),
            List.copyOf(companies),List.copyOf(periods),(int)counts.getOrDefault("FULL_FINANCIAL_CANDIDATE",0L).longValue(),
            0,0,"NO_VERIFIED_PRICE_SERIES",
            PriceReturnPolicy.ID,false,List.of(
                "Financial candidate is not model-ready; no real prices, labels or model evaluation yet",
                "Current stored company list is not a historical investable universe; survivorship bias remains",
                "Annual observations repeated on daily dates are not independent financial periods",
                "Full financial candidates are counts at this cutoff, not continuous historical availability guarantees",
                "Current policy exclusions and warnings are administration/interpretation, not numeric model inputs",
                "Use latest annual period and latest eligible version at EACH prediction date; no old-year fallback",
                "This annual-only pilot does not represent the latest publicly available quarterly financials"));
    }
    private static List<Integer> years(List<Period> ps,String status) {
        return ps.stream().filter(p -> p.status().equals(status)).map(Period::fiscalYear).distinct().sorted().toList();
    }
}
