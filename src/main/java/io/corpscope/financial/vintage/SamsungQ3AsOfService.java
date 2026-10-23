package io.corpscope.financial.vintage;

import io.corpscope.common.NotFoundException;
import io.corpscope.financial.StandardAccount;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static io.corpscope.financial.vintage.FinancialAsOfUnavailableException.Reason.*;

/** Selects the latest known publication first, then requires that exact reviewed version. */
@Service
public class SamsungQ3AsOfService {
    private final JdbcTemplate jdbc;
    public SamsungQ3AsOfService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public QuarterlyAsOfResponse find(long companyId,LocalDate asOf) {
        if(companyId<1 || asOf==null) throw new IllegalArgumentException("Company and as-of date required");
        var companies=jdbc.queryForList("SELECT corp_code FROM company WHERE id=?",String.class,companyId);
        if(companies.isEmpty()) throw new NotFoundException("Company not found");
        if(!"00126380".equals(companies.getFirst()))
            throw new IllegalArgumentException("Quarterly reviewed pilot is limited to Samsung 2025 Q3 CFS");
        var coverage=jdbc.queryForList("""
            SELECT checked_through FROM quarterly_filing_coverage
            WHERE company_id=? AND checked_from<=DATE '2025-09-30' AND checked_through>=CAST(? AS date)-1
            ORDER BY checked_through DESC LIMIT 1
            """,LocalDate.class,companyId,asOf);
        if(coverage.isEmpty()) throw new FinancialAsOfUnavailableException(NO_FILING_COVERAGE,
            "Complete official quarterly filing coverage is unavailable for this date");
        LocalDate through=coverage.getFirst();
        var candidates=jdbc.queryForList("""
            SELECT receipt_number,receipt_date,report_title FROM report_publication
            WHERE company_id=? AND report_title LIKE '%분기보고서 (2025.09)'
              AND receipt_date<? ORDER BY receipt_date DESC LIMIT 2
            """,companyId,asOf);
        if(candidates.isEmpty()) throw new FinancialAsOfUnavailableException(NO_PUBLIC_FILING,
            "No Samsung 2025 Q3 filing was available before this KST date");
        var latest=candidates.getFirst();
        LocalDate date=((java.sql.Date)latest.get("receipt_date")).toLocalDate();
        if(candidates.size()>1 && date.equals(((java.sql.Date)candidates.get(1).get("receipt_date")).toLocalDate()))
            throw new FinancialAsOfUnavailableException(AMBIGUOUS_PUBLICATION_DAY,
                "Same-day quarterly version order requires manual review");
        String receipt=(String)latest.get("receipt_number");
        var versions=jdbc.queryForList("""
            SELECT v.id,v.extraction_profile,d.sha256 FROM quarterly_report_version v
            JOIN report_document d ON d.company_id=v.company_id AND d.receipt_number=v.receipt_number
            WHERE v.company_id=? AND v.fiscal_year=2025 AND v.report_type='Q3' AND v.statement_type='CFS'
              AND v.receipt_number=?
            """,companyId,receipt);
        if(versions.size()!=1) throw new FinancialAsOfUnavailableException(UNREVIEWED_SELECTED_FILING,
            "Latest quarterly filing totals are not reviewed; no older-version fallback");
        var version=versions.getFirst();
        var accounts=jdbc.query("""
            SELECT standard_account,raw_account_name,statement_division,amount,cumulative_amount,currency,display_unit
            FROM quarterly_version_fact WHERE version_id=? ORDER BY standard_account
            """,(rs,n)->new QuarterlyAsOfResponse.Account(StandardAccount.valueOf(rs.getString(1)),rs.getString(2),
                rs.getString(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6),rs.getLong(7)),version.get("id"));
        if(accounts.size()!=6) throw new FinancialAsOfUnavailableException(INCOMPLETE_REVIEWED_VALUES,
            "Reviewed quarterly six totals are incomplete");
        return new QuarterlyAsOfResponse(companyId,companies.getFirst(),2025,"Q3","CFS",asOf,through,receipt,
            date,date.plusDays(1),(String)latest.get("report_title"),(String)version.get("sha256"),
            (String)version.get("extraction_profile"),"REVIEWED_SIX_TOTALS_NOT_MODEL_APPROVED",List.copyOf(accounts),
            List.of("BS_AMOUNT_IS_POINT_IN_TIME_BALANCE","IS_AMOUNT_IS_THREE_MONTH_PERIOD",
                "IS_CUMULATIVE_AMOUNT_IS_YEAR_TO_DATE","COMPARATIVE_PERIOD_NOT_REVIEWED",
                "AS_OF_IS_START_OF_KST_DATE_NOT_INTRADAY_PUBLICATION_TIME"));
    }
}
