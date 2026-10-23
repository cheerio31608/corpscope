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
public class SteelReportReviewService {
    private static final String CORP="00145880";
    private static final Map<Integer,String> RECEIPTS=Map.of(2021,"20220315000651",2022,"20230314001097",
        2023,"20240318000916",2024,"20250317000668",2025,"20260318001506");
    private static final Map<Integer,LocalDate> DATES=Map.of(2021,LocalDate.of(2022,3,15),
        2022,LocalDate.of(2023,3,14),2023,LocalDate.of(2024,3,18),
        2024,LocalDate.of(2025,3,17),2025,LocalDate.of(2026,3,18));
    private final JdbcTemplate jdbc;
    private final SteelAnnualReportParser parser;
    private final ReviewedReportStore store;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public SteelReportReviewService(JdbcTemplate jdbc,SteelAnnualReportParser parser,ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.parser=parser;this.store=new ReviewedReportStore(jdbc,mapper,transactions);
        this.transaction=new TransactionTemplate(transactions);this.mapper=mapper;
    }
    public Map<String,Object> review() {
        List<ReviewedReportStore.Input> inputs=new ArrayList<>();
        Map<Integer,SteelAnnualReportParser.Parsed> parsed=new TreeMap<>();
        for(int year=2021;year<=2025;year++) {
            String receipt=RECEIPTS.get(year);
            var archives=jdbc.queryForList("""
                SELECT d.zip_bytes FROM report_document d JOIN company c ON c.id=d.company_id
                WHERE c.corp_code=? AND d.receipt_number=?
                """,byte[].class,CORP,receipt);
            if(archives.size()!=1) throw new NotFoundException("Preserve all five official Hyundai Steel documents first");
            byte[] zip=archives.getFirst();
            var report=parser.parse(receipt,zip);
            parsed.put(year,report);
            inputs.add(new ReviewedReportStore.Input(year,StatementType.CFS,receipt,DATES.get(year),zip,report.current()));
        }
        Map<Integer,Boolean> match=new TreeMap<>();
        for(int year=2022;year<=2025;year++) {
            var prior=parsed.get(year-1).current().accounts();
            int currentYear=year;
            match.put(year,prior.stream().allMatch(a -> a.amount().compareTo(
                parsed.get(currentYear).comparativeTotals().get(a.standardAccount()))==0));
        }
        return transaction.execute(status -> {
            var saved=store.save(CORP,inputs);
            for(int year=2022;year<=2025;year++) {
                if(!match.get(year)) continue;
                String evidence;
                try {
                    evidence=mapper.writeValueAsString(Map.of("currentReceipt",RECEIPTS.get(year),
                        "previousReceipt",RECEIPTS.get(year-1),"comparativeSourceReceipt",RECEIPTS.get(year),
                        "comparativeTotals",parsed.get(year).comparativeTotals(),"scope","SIX_REPORTED_CFS_TOTALS",
                        "growthMeaning","REPORTED_GROUP_TOTALS_NOT_ORGANIC_GROWTH",
                        "returnDenominator","AVERAGE_OF_TWO_YEAR_END_BALANCES"));
                } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalArgumentException("Cannot serialize comparison evidence");
                }
                var rows=jdbc.queryForList("""
                    INSERT INTO financial_period_comparison_review(current_version_id,previous_version_id,comparison_profile,
                        evidence_available_from_kst_date,evidence)
                    VALUES (?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT(current_version_id,previous_version_id) DO UPDATE SET comparison_profile=EXCLUDED.comparison_profile
                    WHERE financial_period_comparison_review.comparison_profile=EXCLUDED.comparison_profile
                      AND financial_period_comparison_review.evidence_available_from_kst_date=EXCLUDED.evidence_available_from_kst_date
                      AND financial_period_comparison_review.evidence=EXCLUDED.evidence RETURNING current_version_id
                    """,Long.class,saved.get(year-2021).get("versionId"),saved.get(year-2022).get("versionId"),
                    FinancialPeriodComparisonService.steelProfile(year),DATES.get(year).plusDays(1),evidence);
                if(rows.size()!=1) throw new IllegalArgumentException("Hyundai Steel comparison evidence changed");
            }
            return Map.of("versions",saved,"comparisonMatches",match,
                "limitation","Exact original annual CFS six totals only; 2025 later attachment corrections remain unresolved for as-of dates after 2026-03-19");
        });
    }
}
