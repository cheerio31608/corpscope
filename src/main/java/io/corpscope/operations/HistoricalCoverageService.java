package io.corpscope.operations;

import io.corpscope.financial.*;
import io.corpscope.financial.vintage.*;
import io.corpscope.analysis.AnalysisEligibilityProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalCoverageService {
    // Annual scopes actually stored or separately reviewed; filing titles alone do not create a scope.
    private static final String SCOPES="""
        SELECT company_id,fiscal_year,report_type,statement_type FROM financial_fact
        WHERE source='OPENDART' AND report_type='ANNUAL'
        UNION SELECT company_id,fiscal_year,report_type,statement_type FROM financial_report_version
        """;
    private final JdbcTemplate jdbc;
    private final FinancialFeatureService features;
    private final AnalysisEligibilityProperties eligibility;
    public HistoricalCoverageService(JdbcTemplate jdbc,FinancialFeatureService features,AnalysisEligibilityProperties eligibility) {
        this.jdbc=jdbc; this.features=features; this.eligibility=eligibility;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public HistoricalCoverageReport review(LocalDate asOf,int limit,String corpCode) {
        if(asOf==null || limit<1 || limit>500 || corpCode==null || (!corpCode.isBlank() && !corpCode.matches("[0-9]{8}")))
            throw new IllegalArgumentException("Coverage requires an explicit date and limit 1..500");
        String code=corpCode.trim();
        String from=" FROM ("+SCOPES+") s JOIN company c ON c.id=s.company_id WHERE (?='' OR c.corp_code=?)";
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)"+from,Long.class,code,code));
        var scopes=jdbc.query("SELECT c.id,c.corp_code,c.name,s.fiscal_year,s.report_type,s.statement_type"+from+
            " ORDER BY c.corp_code,s.fiscal_year,s.report_type,s.statement_type LIMIT ?",(rs,n) ->
                new ScopeKey(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getInt(4),
                    ReportType.valueOf(rs.getString(5)),StatementType.valueOf(rs.getString(6))),code,code,limit);
        List<HistoricalCoverageReport.Scope> results=new ArrayList<>();
        // Use the same bounded as-of resolver as HTTP/export, rather than duplicating temporal SQL.
        for(var scope:scopes) {
            var candidates=jdbc.query("""
                SELECT p.receipt_number,p.receipt_date,p.report_title,p.correction_marker,
                  EXISTS(SELECT 1 FROM report_document d WHERE d.company_id=p.company_id AND d.receipt_number=p.receipt_number),
                  (SELECT count(*) FROM financial_report_version v JOIN financial_version_fact f ON f.version_id=v.id
                    WHERE v.company_id=p.company_id AND v.fiscal_year=? AND v.report_type=?
                      AND v.statement_type=? AND v.receipt_number=p.receipt_number)=6
                FROM report_publication p WHERE p.company_id=?
                  AND substring(p.report_title from '사업보고서[[:space:]]*[(]([0-9]{4}[.][0-9]{2})[)]')=?
                  AND p.receipt_date<? ORDER BY p.receipt_date DESC,p.receipt_number LIMIT 21
                """,(rs,n) -> new HistoricalCoverageReport.FilingCandidate(rs.getString(1),rs.getObject(2,LocalDate.class),
                    rs.getString(3),rs.getBoolean(4),rs.getBoolean(5),rs.getBoolean(6)),
                scope.year(),scope.report().name(),scope.statement().name(),scope.id(),scope.year()+".12",asOf);
            boolean truncated=candidates.size()>20;
            var shown=List.copyOf(candidates.subList(0,Math.min(candidates.size(),20)));
            String exclusion=eligibility.exclusionReasonFor(scope.code(),scope.year(),scope.report(),scope.statement());
            try {
                var row=features.create(scope.id(),scope.year(),scope.report(),scope.statement(),asOf);
                var evidence=row.evidence();
                results.add(new HistoricalCoverageReport.Scope(scope.id(),scope.code(),scope.name(),scope.year(),scope.report(),
                    scope.statement(),"REVIEWED_VALUES_AVAILABLE",evidence.receiptNumber(),evidence.receiptDate(),
                    evidence.availableFromKstDate(),row.analysisAllowed(),row.exclusionReason(),shown,truncated,
                    nextActions("REVIEWED_VALUES_AVAILABLE",exclusion)));
            } catch(FinancialAsOfUnavailableException ex) {
                results.add(new HistoricalCoverageReport.Scope(scope.id(),scope.code(),scope.name(),scope.year(),scope.report(),
                    scope.statement(),ex.reason().name(),null,null,null,null,exclusion,shown,truncated,
                    nextActions(ex.reason().name(),exclusion)));
            }
        }
        var counts=results.stream().collect(Collectors.groupingBy(HistoricalCoverageReport.Scope::availabilityStatus,TreeMap::new,Collectors.counting()));
        return new HistoricalCoverageReport(asOf,Instant.now(),total,results.size(),total>results.size(),
            Map.copyOf(counts),List.copyOf(results),false,List.of(
                "Administrative review based on currently stored annual scopes; not a historical investment universe",
                "Counts describe returned scopes only; inspect truncated before treating this as a complete review",
                "Reviewed values do not verify cross-period comparability, price alignment or prediction targets",
                "Filing candidates are dated metadata, not automatically confirmed correction chains; receipt order is for display only",
                "Review exclusions are current administrative policy even when values are unavailable; never use them as historical features",
                "Limit 500: per-scope shared resolution is intentional for this pilot; large-universe scans need measured batching"));
    }
    private List<String> nextActions(String status,String exclusion) {
        String action=switch(status) {
            case "REVIEWED_VALUES_AVAILABLE" -> "VERIFY_CROSS_PERIOD_BASIS";
            case "NO_FILING_COVERAGE","OUTSIDE_FILING_COVERAGE" -> "REFRESH_OFFICIAL_FILING_LIST";
            case "AMBIGUOUS_PUBLICATION_DAY" -> "REVIEW_PUBLICATION_ORDER";
            case "UNREVIEWED_SELECTED_FILING" -> "REVIEW_SELECTED_REPORT_TABLES";
            case "INCOMPLETE_REVIEWED_VALUES" -> "REVIEW_INCOMPLETE_VERSION";
            default -> "NO_PUBLIC_REPORT_AT_AS_OF";
        };
        return exclusion==null ? List.of(action):List.of(action,"RESOLVE_ANALYSIS_HOLD");
    }
    private record ScopeKey(long id,String code,String name,int year,ReportType report,StatementType statement) {}
}
