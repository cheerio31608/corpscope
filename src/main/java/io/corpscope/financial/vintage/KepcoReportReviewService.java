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
public class KepcoReportReviewService {
    private final JdbcTemplate jdbc;
    private final KepcoAnnualReportParser parser;
    private final ReviewedReportStore store;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public KepcoReportReviewService(JdbcTemplate jdbc,KepcoAnnualReportParser parser,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.parser=parser; this.store=new ReviewedReportStore(jdbc,mapper,transactions);
        this.transaction=new TransactionTemplate(transactions); this.mapper=mapper;
    }
    public Map<String,Object> review() {
        return review(2025);
    }
    public Map<String,Object> review(int currentYear) {
        if(currentYear<2023 || currentYear>2025) throw new IllegalArgumentException("Unsupported Kepco comparison year");
        Map<Integer,String> receipts=Map.of(2022,"20230525000309",2023,"20240329002856",2024,"20250318000747",2025,"20260317000686");
        Map<String,LocalDate> dates=Map.of("20230320001084",LocalDate.of(2023,3,20),"20230410003765",LocalDate.of(2023,4,10),"20230525000309",LocalDate.of(2023,5,25),"20240318000954",LocalDate.of(2024,3,18),"20240329002856",LocalDate.of(2024,3,29),
            "20250318000747",LocalDate.of(2025,3,18),"20260317000686",LocalDate.of(2026,3,17));
        List<ReviewedReportStore.Input> inputs=new ArrayList<>();
        Map<Integer,KepcoAnnualReportParser.Parsed> parsed=new HashMap<>();
        List<String> reviewReceipts=currentYear==2023 ? List.of("20230320001084","20230410003765",receipts.get(2022),receipts.get(2023)) : currentYear==2024 ? List.of("20240318000954",receipts.get(2023),receipts.get(2024))
            : List.of(receipts.get(2024),receipts.get(2025));
        for(String receipt:reviewReceipts) {
            var archives=jdbc.queryForList("""
                SELECT d.zip_bytes FROM report_document d JOIN company c ON c.id=d.company_id
                WHERE c.corp_code='00159193' AND d.receipt_number=?
                """,byte[].class,receipt);
            if(archives.size()!=1) throw new NotFoundException("Preserve both official documents for the requested Kepco year pair first");
            byte[] zip=archives.getFirst();
            var report=parser.parse(receipt,zip);
            int year=KepcoAnnualReportParser.YEARS.get(receipt);
            parsed.put(year,report);
            inputs.add(new ReviewedReportStore.Input(year,StatementType.CFS,receipt,
                dates.get(receipt),zip,report.current()));
        }
        var originals=parsed.get(currentYear-1).current().accounts();
        boolean match=originals.stream().allMatch(a -> a.amount().compareTo(parsed.get(currentYear).comparativeTotals().get(a.standardAccount()))==0);
        return transaction.execute(status -> {
            var versions=store.save("00159193",inputs);
            if(match) {
                long previous=((Number)versions.get(versions.size()-2).get("versionId")).longValue();
                long current=((Number)versions.getLast().get("versionId")).longValue();
                String evidence;
                try {
                    Map<String,Object> proof=new LinkedHashMap<>(Map.of("currentReceipt",receipts.get(currentYear),"previousReceipt",receipts.get(currentYear-1),
                        "comparativeSourceReceipt",receipts.get(currentYear),"comparativeTotals",parsed.get(currentYear).comparativeTotals(),
                        "scope","SIX_REPORTED_CFS_TOTALS","growthMeaning","REPORTED_GROUP_TOTALS_NOT_ORGANIC_GROWTH",
                        "returnDenominator","AVERAGE_OF_TWO_YEAR_END_BALANCES"));
                    if(currentYear==2023) proof.put("previousPeriodExclusionReason",FinancialPeriodComparisonService.KEPCO_2022_COMPARISON_HOLD);
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
                    """,Long.class,current,previous,FinancialPeriodComparisonService.kepcoProfile(currentYear),dates.get(receipts.get(currentYear)).plusDays(1),evidence);
                if(saved.size()!=1) throw new IllegalArgumentException("Comparison evidence changed; manual review required");
            }
            return Map.of("versions",versions,"sixComparativesMatch",match,"comparativeEvidenceReceipt",receipts.get(currentYear),
                "crossPeriodMetricsApproved",match,"limitation","Only the exact reviewed version pair and six reported CFS totals; not organic growth or all accounting disclosures");
        });
    }
}
