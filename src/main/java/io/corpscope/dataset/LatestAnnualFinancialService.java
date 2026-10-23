package io.corpscope.dataset;

import io.corpscope.common.NotFoundException;
import io.corpscope.financial.*;
import io.corpscope.financial.vintage.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Annual-only pilot: select the latest published fiscal period BEFORE checking review status. */
@Service
public class LatestAnnualFinancialService {
    public record Company(long id,String corpCode,String stockCode) {}
    private final JdbcTemplate jdbc;
    private final FinancialFeatureService features;
    public LatestAnnualFinancialService(JdbcTemplate jdbc,FinancialFeatureService features) {
        this.jdbc=jdbc;this.features=features;
    }
    public Company company(long id) {
        var rows=jdbc.query("SELECT id,corp_code,stock_code FROM company WHERE id=?",
            (rs,n) -> new Company(rs.getLong(1),rs.getString(2),rs.getString(3)),id);
        if(rows.size()!=1) throw new NotFoundException("Company not found");
        return rows.getFirst();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public FinancialFeatureResponse latest(long company,LocalDate asOf) {
        if(asOf==null) throw new IllegalArgumentException("Explicit decision date required");
        company(company);
        var years=jdbc.queryForList("""
            SELECT DISTINCT substring(report_title from '사업보고서[[:space:]]*[(]([0-9]{4})[.]12[)]')::int AS fiscal_year
            FROM report_publication WHERE company_id=? AND receipt_date<?
              AND report_title ~ '사업보고서[[:space:]]*[(][0-9]{4}[.]12[)]'
            ORDER BY fiscal_year DESC LIMIT 1
            """,Integer.class,company,asOf);
        if(years.isEmpty()) throw new FinancialAsOfUnavailableException(
            FinancialAsOfUnavailableException.Reason.NO_PUBLIC_FILING,"No annual filing before decision date");
        // An unreviewed new year or correction blocks the request; never fall back to old approved data.
        return features.create(company,years.getFirst(),ReportType.ANNUAL,StatementType.CFS,asOf);
    }
}
