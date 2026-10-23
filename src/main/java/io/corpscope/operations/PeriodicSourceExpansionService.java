package io.corpscope.operations;

import io.corpscope.financial.ReportType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Local, bounded source collection. A stored ZIP is not a reviewed financial version. */
@Service
public class PeriodicSourceExpansionService {
    private final PeriodicFilingCoverageService coverage;
    private final ReportDocumentCollectionService documents;
    private final JdbcTemplate jdbc;

    public PeriodicSourceExpansionService(PeriodicFilingCoverageService coverage,
            ReportDocumentCollectionService documents, JdbcTemplate jdbc) {
        this.coverage=coverage; this.documents=documents; this.jdbc=jdbc;
    }

    public record Scope(String corpCode, int fiscalYear, ReportType reportType, LocalDate through) {}
    public record Request(List<Scope> scopes) {}
    public record Outcome(Scope scope, String status, int candidates, int archivesStored,
            List<String> receiptNumbers, String reason) {}

    public List<Outcome> collect(Request request) {
        if(request==null || request.scopes()==null || request.scopes().isEmpty()
                || request.scopes().size()>100 || new HashSet<>(request.scopes()).size()!=request.scopes().size())
            throw new IllegalArgumentException("Provide 1..100 distinct periodic scopes");
        List<Outcome> outcomes=new ArrayList<>();
        for(Scope scope:request.scopes()) {
            if(scope==null || scope.corpCode()==null || !scope.corpCode().matches("[0-9]{8}")
                    || scope.reportType()==null || scope.reportType()==ReportType.ANNUAL
                    || scope.through()==null || scope.fiscalYear()<2016 || scope.fiscalYear()>2100)
                throw new IllegalArgumentException("Invalid periodic scope");
        }
        for(Scope scope:request.scopes()) {
            try {
                coverage.collect(scope.corpCode(),scope.fiscalYear(),scope.reportType(),scope.through());
                int month=switch(scope.reportType()) {
                    case Q1 -> 3; case HALF_YEAR -> 6; case Q3 -> 9;
                    default -> throw new IllegalArgumentException("Quarterly scope required");
                };
                LocalDate from=LocalDate.of(scope.fiscalYear(),month,1).withDayOfMonth(
                    LocalDate.of(scope.fiscalYear(),month,1).lengthOfMonth());
                String title=(scope.reportType()==ReportType.HALF_YEAR?"반기보고서 (":"분기보고서 (")
                    +scope.fiscalYear()+"."+String.format(Locale.ROOT,"%02d",month)+")";
                List<String> receipts=jdbc.queryForList("""
                    SELECT p.receipt_number FROM report_publication p JOIN company c ON c.id=p.company_id
                    WHERE c.corp_code=? AND p.receipt_date BETWEEN ? AND ?
                      AND right(p.report_title,length(?))=? ORDER BY p.receipt_date,p.receipt_number
                    """,String.class,scope.corpCode(),from,scope.through(),title,title);
                if(receipts.isEmpty()) {
                    outcomes.add(new Outcome(scope,"NO_EXACT_PERIOD_FILING",0,0,List.of(),
                        "Complete list contains no exact period title"));
                    continue;
                }
                if(receipts.size()>5) {
                    outcomes.add(new Outcome(scope,"TOO_MANY_CANDIDATES",receipts.size(),0,receipts,
                        "Review and collect receipts in bounded groups"));
                    continue;
                }
                List<String> missing=jdbc.queryForList("""
                    SELECT p.receipt_number FROM report_publication p JOIN company c ON c.id=p.company_id
                    LEFT JOIN report_document d ON d.company_id=p.company_id AND d.receipt_number=p.receipt_number
                    WHERE c.corp_code=? AND p.receipt_number IN (%s) AND d.receipt_number IS NULL
                    """.formatted(String.join(",",Collections.nCopies(receipts.size(),"?"))),
                    String.class,concat(scope.corpCode(),receipts));
                if(!missing.isEmpty()) documents.collect(scope.corpCode(),missing);
                outcomes.add(new Outcome(scope,"SOURCE_ARCHIVED_NOT_REVIEWED",receipts.size(),missing.size(),
                    receipts,"Official list and ZIP only; account review still required"));
            } catch(RuntimeException ex) {
                outcomes.add(new Outcome(scope,"SOURCE_COLLECTION_FAILED",0,0,List.of(),
                    ex.getClass().getSimpleName()));
            }
        }
        return List.copyOf(outcomes);
    }

    private Object[] concat(String corpCode,List<String> receipts) {
        Object[] values=new Object[receipts.size()+1]; values[0]=corpCode;
        for(int i=0;i<receipts.size();i++) values[i+1]=receipts.get(i);
        return values;
    }
}
