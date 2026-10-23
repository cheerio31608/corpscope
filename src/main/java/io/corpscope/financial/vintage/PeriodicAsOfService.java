package io.corpscope.financial.vintage;

import io.corpscope.common.NotFoundException;
import io.corpscope.financial.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static io.corpscope.financial.vintage.FinancialAsOfUnavailableException.Reason.*;

/** Exact reviewed quarter only; source-list coverage and latest-publication selection precede value lookup. */
@Service
public class PeriodicAsOfService {
    private final JdbcTemplate jdbc;
    public PeriodicAsOfService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public QuarterlyAsOfResponse find(long companyId,int year,ReportType report,StatementType statement,LocalDate asOf) {
        if(companyId<1 || year<2016 || year>2100 || report==null || report==ReportType.ANNUAL
            || statement==null || asOf==null) throw new IllegalArgumentException("Exact periodic as-of scope required");
        var codes=jdbc.queryForList("SELECT corp_code FROM company WHERE id=?",String.class,companyId);
        if(codes.isEmpty()) throw new NotFoundException("Company not found");
        int month=switch(report) {case Q1 -> 3;case HALF_YEAR -> 6;case Q3 -> 9;default -> throw new IllegalArgumentException();};
        LocalDate periodEnd=LocalDate.of(year,month,1).withDayOfMonth(LocalDate.of(year,month,1).lengthOfMonth());
        var through=jdbc.queryForList("""
            SELECT checked_through FROM periodic_filing_coverage
            WHERE company_id=? AND fiscal_year=? AND report_type=? AND checked_from<=?
              AND checked_through>=CAST(? AS date)-1
            ORDER BY checked_through DESC LIMIT 1
            """,LocalDate.class,companyId,year,report.name(),periodEnd,asOf);
        if(through.isEmpty()) throw new FinancialAsOfUnavailableException(NO_FILING_COVERAGE,
            "Complete official periodic filing coverage is unavailable for this date");
        String suffix=(report==ReportType.HALF_YEAR?"반기보고서 (":"분기보고서 (")
            +year+"."+String.format(Locale.ROOT,"%02d",month)+")";
        var candidates=jdbc.queryForList("""
            SELECT receipt_number,receipt_date,report_title FROM report_publication
            WHERE company_id=? AND right(report_title,length(?))=? AND receipt_date<?
            ORDER BY receipt_date DESC LIMIT 2
            """,companyId,suffix,suffix,asOf);
        if(candidates.isEmpty()) throw new FinancialAsOfUnavailableException(NO_PUBLIC_FILING,
            "No matching periodic filing was available before this KST date");
        var latest=candidates.getFirst();
        LocalDate date=((java.sql.Date)latest.get("receipt_date")).toLocalDate();
        if(candidates.size()>1 && date.equals(((java.sql.Date)candidates.get(1).get("receipt_date")).toLocalDate()))
            throw new FinancialAsOfUnavailableException(AMBIGUOUS_PUBLICATION_DAY,
                "Same-day periodic version order requires explicit review");
        String receipt=(String)latest.get("receipt_number");
        var versions=jdbc.queryForList("""
            SELECT v.id,v.extraction_profile,d.sha256 FROM quarterly_report_version v
            JOIN report_document d ON d.company_id=v.company_id AND d.receipt_number=v.receipt_number
            WHERE v.company_id=? AND v.fiscal_year=? AND v.report_type=? AND v.statement_type=?
              AND v.receipt_number=?
            """,companyId,year,report.name(),statement.name(),receipt);
        if(versions.size()!=1) throw new FinancialAsOfUnavailableException(UNREVIEWED_SELECTED_FILING,
            "Latest periodic filing is not reviewed; no older-version fallback");
        var version=versions.getFirst();
        var accounts=jdbc.query("""
            SELECT standard_account,raw_account_name,statement_division,amount,cumulative_amount,currency,display_unit
            FROM quarterly_version_fact WHERE version_id=? ORDER BY standard_account
            """,(rs,n)->new QuarterlyAsOfResponse.Account(StandardAccount.valueOf(rs.getString(1)),rs.getString(2),
                rs.getString(3),rs.getBigDecimal(4),rs.getBigDecimal(5),rs.getString(6),rs.getLong(7)),version.get("id"));
        if(accounts.size()!=6) throw new FinancialAsOfUnavailableException(INCOMPLETE_REVIEWED_VALUES,
            "Reviewed periodic six totals are incomplete");
        return new QuarterlyAsOfResponse(companyId,codes.getFirst(),year,report.name(),statement.name(),asOf,
            through.getFirst(),receipt,date,date.plusDays(1),(String)latest.get("report_title"),
            (String)version.get("sha256"),(String)version.get("extraction_profile"),
            "REVIEWED_SIX_TOTALS_NOT_MODEL_APPROVED",List.copyOf(accounts),
            List.of("BS_AMOUNT_IS_POINT_IN_TIME_BALANCE","INCOME_AMOUNT_IS_THREE_MONTH_PERIOD",
                "INCOME_CUMULATIVE_AMOUNT_IS_YEAR_TO_DATE","COMPARATIVE_PERIOD_NOT_REVIEWED",
                "AS_OF_IS_START_OF_KST_DATE_NOT_INTRADAY_PUBLICATION_TIME"));
    }
}
