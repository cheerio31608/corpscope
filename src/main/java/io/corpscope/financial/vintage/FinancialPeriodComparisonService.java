package io.corpscope.financial.vintage;

import io.corpscope.analysis.AnalysisEligibilityProperties;
import io.corpscope.financial.StandardAccount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FinancialPeriodComparisonService {
    // These holds concern 2022 versus 2021. The reviewed 2023 pair may use 2022 current totals
    // only while the exact reason remains unchanged; a new hold must be reviewed again.
    static final String HYNIX_2022_COMPARISON_HOLD="SK hynix: final Intel NAND acquisition accounting restated 2021 assets and liabilities; receipt 20230321001209";
    static final String KEPCO_2022_COMPARISON_HOLD="KEPCO: K-IFRS 1016 amendment restated 2021 income and balance comparisons; receipt 20230525000309";
    static final String HYUNDAI_MOTOR_2023_COMPARISON_HOLD="Hyundai Motor: Russian HMMR discontinued operations changed 2022 comparative revenue and operating profit; receipt 20240313001451";
    public static String samsungProfile(int year) {
        if(year<2021 || year>2025) throw new IllegalArgumentException("Unsupported Samsung comparison year");
        return "SAMSUNG_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String hynixProfile() { return "HYNIX_2024_2025_CFS_SIX_TOTALS_V1"; }
    public static String hynixProfile(int year) {
        if(year<2023 || year>2025) throw new IllegalArgumentException("Unsupported Hynix comparison year");
        return "HYNIX_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String kepcoProfile() { return "KEPCO_2024_2025_CFS_SIX_TOTALS_V1"; }
    public static String kepcoProfile(int year) {
        if(year<2023 || year>2025) throw new IllegalArgumentException("Unsupported Kepco comparison year");
        return "KEPCO_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String naverProfile() { return "NAVER_2024_2025_CFS_SIX_TOTALS_V1"; }
    public static String naverProfile(int year) {
        if(year<2022 || year>2025) throw new IllegalArgumentException("Unsupported Naver comparison year");
        return "NAVER_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String lgesProfile(int year) {
        if(year<2022 || year>2025) throw new IllegalArgumentException("Unsupported LG Energy Solution comparison year");
        return "LGES_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String hdhhiProfile(int year) {
        if(year<2023 || year>2025) throw new IllegalArgumentException("Unsupported HD Hyundai Heavy Industries comparison year");
        return "HDHHI_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String steelProfile(int year) {
        if(year<2022 || year>2025) throw new IllegalArgumentException("Unsupported Hyundai Steel comparison year");
        return "STEEL_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String kiaProfile(int year) {
        if(year<2022 || year>2025) throw new IllegalArgumentException("Unsupported Kia comparison year");
        return "KIA_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    public static String kogasProfile(int year) {
        if(year<2023 || year>2025) throw new IllegalArgumentException("Unsupported Korea Gas Corporation comparison year");
        return "KOGAS_"+(year-1)+"_"+year+
            (year==2024 ? "_CFS_SIX_TOTALS_ROUNDED_MILLION_V1":"_CFS_SIX_TOTALS_V1");
    }
    public static String hyundaiMotorProfile(int year) {
        if(year!=2022 && year!=2024 && year!=2025) throw new IllegalArgumentException("Unsupported Hyundai Motor comparison year");
        return "HYUNDAI_MOTOR_"+(year-1)+"_"+year+"_CFS_SIX_TOTALS_V1";
    }
    private final JdbcTemplate jdbc;
    private final FinancialAsOfService financials;
    private final AnalysisEligibilityProperties eligibility;
    public FinancialPeriodComparisonService(JdbcTemplate jdbc,FinancialAsOfService financials,AnalysisEligibilityProperties eligibility) {
        this.jdbc=jdbc; this.financials=financials; this.eligibility=eligibility;
    }
    public Optional<FinancialFeatureResponse.ComparisonEvidence> resolve(FinancialAsOfResponse current) {
        String prefix;
        String profile;
        if(current.corpCode().equals("00126380") && current.fiscalYear()>=2021 && current.fiscalYear()<=2025) {
            prefix="SAMSUNG"; profile=samsungProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00164779") && (current.fiscalYear()>=2023 && current.fiscalYear()<=2025)) {
            prefix="HYNIX"; profile=hynixProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00159193") && (current.fiscalYear()>=2023 && current.fiscalYear()<=2025)) {
            prefix="KEPCO"; profile=kepcoProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00266961") && current.fiscalYear()>=2022 && current.fiscalYear()<=2025) {
            prefix="NAVER"; profile=naverProfile(current.fiscalYear());
        } else if(current.corpCode().equals("01515323") && current.fiscalYear()>=2022 && current.fiscalYear()<=2025) {
            prefix="LGES"; profile=lgesProfile(current.fiscalYear());
        } else if(current.corpCode().equals("01390344") && current.fiscalYear()>=2023 && current.fiscalYear()<=2025) {
            prefix="HDHHI"; profile=hdhhiProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00145880") && current.fiscalYear()>=2022 && current.fiscalYear()<=2025) {
            prefix="STEEL"; profile=steelProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00106641") && current.fiscalYear()>=2022 && current.fiscalYear()<=2025) {
            prefix="KIA"; profile=kiaProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00261285") && current.fiscalYear()>=2023 && current.fiscalYear()<=2025) {
            prefix="KOGAS"; profile=kogasProfile(current.fiscalYear());
        } else if(current.corpCode().equals("00164742") &&
                (current.fiscalYear()==2022 || current.fiscalYear()==2024 || current.fiscalYear()==2025)) {
            prefix="HYUNDAI_MOTOR"; profile=hyundaiMotorProfile(current.fiscalYear());
        } else return Optional.empty();
        String previousExclusion=eligibility.exclusionReasonFor(current.corpCode(),current.fiscalYear()-1,
            current.reportType(),current.statementType());
        String reviewedHold=current.fiscalYear()==2023 ? switch(prefix) {
            case "HYNIX" -> HYNIX_2022_COMPARISON_HOLD;
            case "KEPCO" -> KEPCO_2022_COMPARISON_HOLD;
            default -> null;
        } : current.fiscalYear()==2024 && prefix.equals("HYUNDAI_MOTOR")
            ? HYUNDAI_MOTOR_2023_COMPARISON_HOLD : null;
        if(previousExclusion!=null && (eligibility.excludedCompanyReasons().containsKey(current.corpCode()) ||
            !previousExclusion.equals(reviewedHold))) return Optional.empty();
        var reviews=jdbc.queryForList("""
            SELECT p.receipt_number,d.sha256,r.comparison_profile,r.evidence_available_from_kst_date,
                   r.evidence->>'previousPeriodExclusionReason' AS reviewed_previous_hold
            FROM financial_period_comparison_review r JOIN financial_report_version c ON c.id=r.current_version_id
            JOIN report_document cd ON cd.company_id=c.company_id AND cd.receipt_number=c.receipt_number
            JOIN financial_report_version p ON p.id=r.previous_version_id
            JOIN report_document d ON d.company_id=p.company_id AND d.receipt_number=p.receipt_number
            WHERE c.company_id=? AND c.fiscal_year=? AND c.report_type=? AND c.statement_type=?
              AND c.receipt_number=? AND cd.sha256=? AND p.company_id=c.company_id
              AND c.extraction_profile=?
              AND p.extraction_profile=?
              AND p.fiscal_year=c.fiscal_year-1 AND p.report_type=c.report_type AND p.statement_type=c.statement_type
              AND (SELECT count(*) FROM financial_version_fact f WHERE f.version_id=p.id
                AND CASE WHEN r.comparison_profile='KOGAS_2023_2024_CFS_SIX_TOTALS_ROUNDED_MILLION_V1'
                    AND f.display_unit=1 AND (SELECT count(*) FROM financial_version_fact cf
                        WHERE cf.version_id=c.id AND cf.display_unit=1000000)=6
                    AND r.evidence->>'comparisonMethod'='ROUND_HALF_UP_TO_MILLION'
                    THEN round(f.amount/1000000,0)*1000000 ELSE f.amount END
                  =CASE WHEN jsonb_typeof(r.evidence->'comparativeTotals'->f.standard_account)='number'
                    THEN (r.evidence->'comparativeTotals'->>f.standard_account)::numeric ELSE NULL END)=6
              AND r.comparison_profile=? AND r.evidence_available_from_kst_date<=?
            """,current.companyId(),current.fiscalYear(),current.reportType().name(),current.statementType().name(),
            current.receiptNumber(),current.documentSha256(),
            prefix+"_"+current.fiscalYear()+"_ANNUAL_CFS_SIX_TOTALS_V1",
            prefix+"_"+(current.fiscalYear()-1)+"_ANNUAL_CFS_SIX_TOTALS_V1",profile,current.asOf());
        if(reviews.size()!=1) return Optional.empty();
        if(previousExclusion!=null && !previousExclusion.equals(reviews.getFirst().get("reviewed_previous_hold")))
            return Optional.empty();
        FinancialAsOfResponse previous;
        try { previous=financials.find(current.companyId(),current.fiscalYear()-1,current.reportType(),current.statementType(),current.asOf()); }
        catch(FinancialAsOfUnavailableException ex) { return Optional.empty(); }
        var review=reviews.getFirst();
        if(!previous.receiptNumber().equals(review.get("receipt_number")) || !previous.documentSha256().equals(review.get("sha256"))) return Optional.empty();
        Map<StandardAccount,BigDecimal> values=new EnumMap<>(StandardAccount.class);
        for(var account:previous.accounts()) values.put(account.standardAccount(),account.amount());
        return Optional.of(new FinancialFeatureResponse.ComparisonEvidence((String)review.get("comparison_profile"),
            ((java.sql.Date)review.get("evidence_available_from_kst_date")).toLocalDate(),previous.fiscalYear(),
            new FinancialFeatureResponse.Evidence(previous.receiptNumber(),previous.receiptDate(),previous.availableFromKstDate(),
                previous.documentSha256(),previous.extractionProfile(),previous.source(),"KRW"),Map.copyOf(values)));
    }
}
