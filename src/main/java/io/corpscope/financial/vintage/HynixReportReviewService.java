package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.common.NotFoundException;
import io.corpscope.financial.StatementType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HynixReportReviewService {
    private final JdbcTemplate jdbc;
    private final HynixAnnualReportParser parser;
    private final ReviewedReportStore store;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public HynixReportReviewService(JdbcTemplate jdbc,HynixAnnualReportParser parser,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.parser=parser; this.store=new ReviewedReportStore(jdbc,mapper,transactions);
        this.transaction=new TransactionTemplate(transactions); this.mapper=mapper;
    }
    public Map<String,Object> review() {
        return review(2025);
    }
    public Map<String,Object> review(int currentYear) {
        if(currentYear<2023 || currentYear>2025) throw new IllegalArgumentException("Unsupported Hynix comparison year");
        Map<Integer,String> receipts=Map.of(2022,"20230321001209",2023,"20240319000684",2024,"20250319000665",2025,"20260317000635");
        Map<Integer,LocalDate> dates=Map.of(2022,LocalDate.of(2023,3,21),2023,LocalDate.of(2024,3,19),2024,LocalDate.of(2025,3,19),2025,LocalDate.of(2026,3,17));
        List<ReviewedReportStore.Input> inputs=new ArrayList<>();
        Map<Integer,HynixAnnualReportParser.Parsed> parsed=new HashMap<>();
        for(String receipt:List.of(receipts.get(currentYear-1),receipts.get(currentYear))) {
            var archives=jdbc.queryForList("""
                SELECT d.zip_bytes FROM report_document d JOIN company c ON c.id=d.company_id
                WHERE c.corp_code='00164779' AND d.receipt_number=?
                """,byte[].class,receipt);
            if(archives.size()!=1) throw new NotFoundException("Preserve both official documents for the requested Hynix year pair first");
            byte[] zip=archives.getFirst();
            var report=parser.parse(receipt,zip);
            int year=HynixAnnualReportParser.YEARS.get(receipt);
            parsed.put(year,report);
            inputs.add(new ReviewedReportStore.Input(year,StatementType.CFS,receipt,
                dates.get(year),zip,report.current()));
        }
        var originals=parsed.get(currentYear-1).current().accounts();
        boolean match=originals.stream().allMatch(a -> a.amount().compareTo(parsed.get(currentYear).comparativeTotals().get(a.standardAccount()))==0);
        return transaction.execute(status -> {
            var versions=store.save("00164779",inputs);
            if(match) {
                long previous=((Number)versions.get(0).get("versionId")).longValue();
                long current=((Number)versions.get(1).get("versionId")).longValue();
                String evidence;
                try {
                    Map<String,Object> proof=new LinkedHashMap<>(Map.of("currentReceipt",receipts.get(currentYear),"previousReceipt",receipts.get(currentYear-1),
                        "comparativeSourceReceipt",receipts.get(currentYear),"comparativeTotals",parsed.get(currentYear).comparativeTotals(),
                        "scope","SIX_REPORTED_CFS_TOTALS","growthMeaning","REPORTED_GROUP_TOTALS_NOT_ORGANIC_GROWTH",
                        "returnDenominator","AVERAGE_OF_TWO_YEAR_END_BALANCES"));
                    if(currentYear==2023) proof.put("previousPeriodExclusionReason",FinancialPeriodComparisonService.HYNIX_2022_COMPARISON_HOLD);
                    evidence=mapper.writeValueAsString(proof);
                } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("Cannot serialize comparison evidence"); }
                var saved=jdbc.queryForList("""
                    INSERT INTO financial_period_comparison_review(current_version_id,previous_version_id,comparison_profile,
                        evidence_available_from_kst_date,evidence)
                    VALUES (?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT(current_version_id,previous_version_id) DO UPDATE SET comparison_profile=EXCLUDED.comparison_profile
                    WHERE financial_period_comparison_review.comparison_profile=EXCLUDED.comparison_profile
                      AND financial_period_comparison_review.evidence_available_from_kst_date=EXCLUDED.evidence_available_from_kst_date
                      AND financial_period_comparison_review.evidence=EXCLUDED.evidence RETURNING current_version_id
                    """,Long.class,current,previous,FinancialPeriodComparisonService.hynixProfile(currentYear),dates.get(currentYear).plusDays(1),evidence);
                if(saved.size()!=1) throw new IllegalArgumentException("Comparison evidence changed; manual review required");
            }
            return Map.of("versions",versions,"sixComparativesMatch",match,"comparativeEvidenceReceipt",receipts.get(currentYear),
                "crossPeriodMetricsApproved",match,"limitation","Only the exact reviewed version pair and six reported CFS totals; not organic growth or all accounting disclosures");
        });
    }
}
