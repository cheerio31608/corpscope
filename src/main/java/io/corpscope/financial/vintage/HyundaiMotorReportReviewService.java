package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.common.NotFoundException;
import io.corpscope.financial.StandardAccount;
import io.corpscope.financial.StatementType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reviews only the seven named Hyundai Motor annual CFS filings, including both written corrections. */
@Service
public class HyundaiMotorReportReviewService {
    private static final String CORP="00164742";
    private record Filing(int year,String receipt,LocalDate date) {}
    private static final List<Filing> FILINGS=List.of(
        new Filing(2021,"20220316001052",LocalDate.of(2022,3,16)),
        new Filing(2022,"20230315001030",LocalDate.of(2023,3,15)),
        new Filing(2023,"20240313001451",LocalDate.of(2024,3,13)),
        new Filing(2021,"20240314001466",LocalDate.of(2024,3,14)),
        new Filing(2022,"20240314001531",LocalDate.of(2024,3,14)),
        new Filing(2024,"20250312001148",LocalDate.of(2025,3,12)),
        new Filing(2025,"20260318001394",LocalDate.of(2026,3,18)));
    private final JdbcTemplate jdbc;
    private final HyundaiMotorAnnualReportParser parser;
    private final ReviewedReportStore store;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    public HyundaiMotorReportReviewService(JdbcTemplate jdbc,HyundaiMotorAnnualReportParser parser,ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.parser=parser;this.mapper=mapper;
        this.store=new ReviewedReportStore(jdbc,mapper,transactions);
        this.transaction=new TransactionTemplate(transactions);
    }

    public Map<String,Object> review() {
        List<ReviewedReportStore.Input> inputs=new ArrayList<>();
        Map<String,HyundaiMotorAnnualReportParser.Parsed> parsed=new LinkedHashMap<>();
        for(Filing filing:FILINGS) {
            var archives=jdbc.queryForList("""
                SELECT d.zip_bytes FROM report_document d JOIN company c ON c.id=d.company_id
                WHERE c.corp_code=? AND d.receipt_number=?
                """,byte[].class,CORP,filing.receipt());
            if(archives.size()!=1) throw new NotFoundException("Preserve all seven named Hyundai Motor documents first");
            byte[] zip=archives.getFirst();
            var result=parser.parse(filing.receipt(),zip);
            verifyStoredTotals(filing.year(),result.current());
            parsed.put(filing.receipt(),result);
            inputs.add(new ReviewedReportStore.Input(filing.year(),StatementType.CFS,
                filing.receipt(),filing.date(),zip,result.current()));
        }
        // Each review remains within the store's bounded five-version write; the outer
        // transaction keeps the two writes and all comparison evidence atomic.
        return transaction.execute(status -> {
            List<Map<String,Object>> saved=new ArrayList<>(store.save(CORP,inputs.subList(0,5)));
            saved.addAll(store.save(CORP,inputs.subList(5,7)));
            Map<String,Long> ids=new HashMap<>();
            for(int i=0;i<FILINGS.size();i++) ids.put(FILINGS.get(i).receipt(),(Long)saved.get(i).get("versionId"));
            int comparisons=0;
            for(int i=1;i<FILINGS.size();i++) {
                Filing current=FILINGS.get(i);
                // The 2023 filing restates 2022 revenue and operating profit for
                // discontinued operations, so no 2023/2022 pair is approved here.
                // The 2024/2023 pair may use the exact 2023 current totals while
                // preserving the separate 2023/2022 analysis exclusion.
                String previousReceipt=switch(current.receipt()) {
                    case "20230315001030" -> "20220316001052";
                    case "20240314001531" -> "20240314001466";
                    case "20250312001148" -> "20240313001451";
                    case "20260318001394" -> "20250312001148";
                    default -> null;
                };
                if(previousReceipt==null) continue;
                var currentParsed=parsed.get(current.receipt());
                var previous=parsed.get(previousReceipt).current();
                if(previous.accounts().stream().anyMatch(a -> a.amount().compareTo(
                        currentParsed.comparativeTotals().get(a.standardAccount()))!=0)) continue;
                String evidence;
                try {
                    Map<String,Object> proof=new HashMap<>(Map.of("currentReceipt",current.receipt(),
                        "previousReceipt",previousReceipt,"comparativeSourceReceipt",current.receipt(),
                        "comparativeTotals",currentParsed.comparativeTotals(),
                        "scope","SIX_REPORTED_CFS_TOTALS",
                        "growthMeaning","REPORTED_GROUP_TOTALS_NOT_ORGANIC_GROWTH",
                        "returnDenominator","AVERAGE_OF_TWO_YEAR_END_BALANCES"));
                    if(current.year()==2024) proof.put("previousPeriodExclusionReason",
                        FinancialPeriodComparisonService.HYUNDAI_MOTOR_2023_COMPARISON_HOLD);
                    evidence=mapper.writeValueAsString(proof);
                } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalArgumentException("Cannot serialize Hyundai Motor comparison evidence");
                }
                var rows=jdbc.queryForList("""
                    INSERT INTO financial_period_comparison_review(current_version_id,previous_version_id,
                        comparison_profile,evidence_available_from_kst_date,evidence)
                    VALUES (?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT(current_version_id,previous_version_id) DO UPDATE SET comparison_profile=EXCLUDED.comparison_profile
                    WHERE financial_period_comparison_review.comparison_profile=EXCLUDED.comparison_profile
                      AND financial_period_comparison_review.evidence_available_from_kst_date=EXCLUDED.evidence_available_from_kst_date
                      AND financial_period_comparison_review.evidence=EXCLUDED.evidence RETURNING current_version_id
                    """,Long.class,ids.get(current.receipt()),ids.get(previousReceipt),
                    FinancialPeriodComparisonService.hyundaiMotorProfile(current.year()),current.date().plusDays(1),evidence);
                if(rows.size()!=1) throw new IllegalArgumentException("Hyundai Motor comparison evidence changed");
                comparisons++;
            }
            return Map.of("versions",saved,"matchingComparisonPairs",comparisons,
                "limitation","Exact Hyundai Motor annual CFS six totals only; 2023/2022 restatement remains held, 2024/2023 uses the reviewed 2023 current version without lifting its exclusion");
        });
    }

    private void verifyStoredTotals(int year,ReviewedReport report) {
        for(var account:report.accounts()) {
            StandardAccount standard=account.standardAccount();
            var amounts=jdbc.queryForList("""
                SELECT f.amount FROM financial_fact f JOIN company c ON c.id=f.company_id
                WHERE c.corp_code=? AND f.fiscal_year=? AND f.report_type='ANNUAL'
                  AND f.statement_type='CFS' AND f.source='OPENDART'
                  AND f.statement_division=? AND f.standard_account=?
                """,java.math.BigDecimal.class,CORP,year,account.statementDivision(),standard.name());
            if(amounts.size()!=1 || amounts.getFirst().compareTo(account.amount())!=0)
                throw new IllegalArgumentException("Hyundai Motor official total differs from the stored OpenDART total: "+year+" "+standard);
        }
    }
}
