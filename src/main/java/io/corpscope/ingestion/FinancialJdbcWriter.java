package io.corpscope.ingestion;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** All writes join the chunk transaction, including raw snapshots and period replacement. */
@Component
public class FinancialJdbcWriter implements ItemWriter<CollectedCompany> {
    private static final String INSERT_FACT = """
            INSERT INTO financial_fact(company_id,fiscal_year,report_type,statement_type,
              statement_division,account_key,account_code,standard_account,raw_account_name,
              account_detail,amount,cumulative_amount,currency,receipt_number,source,raw_ingestion_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
    private final JdbcTemplate jdbc;
    private final int batchSize;

    @Autowired
    public FinancialJdbcWriter(JdbcTemplate jdbc) { this(jdbc, 500); }

    /** Explicit size is used by the isolated performance comparison; production retains 500. */
    public FinancialJdbcWriter(JdbcTemplate jdbc, int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.jdbc = jdbc;
        this.batchSize = batchSize;
    }

    @Override
    public void write(Chunk<? extends CollectedCompany> chunk) {
        for (CollectedCompany collected : chunk) {
            CompanyData c = collected.company();
            // This upsert also locks the company row until commit: concurrent periods for the
            // same company serialize, so delete+insert cannot interleave.
            Long companyId = jdbc.queryForObject("""
                    INSERT INTO company(corp_code,name,stock_code,corporate_registration_number,
                      business_registration_number,ceo,industry_code,established_date,address,homepage)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(corp_code) DO UPDATE SET name=EXCLUDED.name,stock_code=EXCLUDED.stock_code,
                      corporate_registration_number=EXCLUDED.corporate_registration_number,
                      business_registration_number=EXCLUDED.business_registration_number,
                      ceo=EXCLUDED.ceo,industry_code=EXCLUDED.industry_code,
                      established_date=EXCLUDED.established_date,address=EXCLUDED.address,
                      homepage=EXCLUDED.homepage,updated_at=now()
                    RETURNING id
                    """, Long.class, c.corpCode(), c.name(), c.stockCode(), c.corporateRegistrationNumber(),
                    c.businessRegistrationNumber(), c.ceo(), c.industryCode(), c.establishedDate(), c.address(), c.homepage());
            snapshot(companyId, "company", c.corpCode(), collected.companyJson());
            var period = collected.period();
            String requestKey = period.fiscalYear() + ":" + period.reportType() + ":" + period.statementType();
            Long rawId = snapshot(companyId, "fnlttSinglAcntAll", requestKey, collected.financialJson());
            if (collected.noFinancialData()) continue; // 013 never erases previously collected data.
            jdbc.update("""
                    DELETE FROM financial_fact WHERE company_id=? AND fiscal_year=?
                    AND report_type=? AND statement_type=?
                    """, companyId, period.fiscalYear(), period.reportType().name(), period.statementType().name());
            List<FinancialData> rows = collected.financials();
            jdbc.batchUpdate(INSERT_FACT, rows, batchSize, (ps, row) -> bindFact(ps, companyId, rawId, collected, row));
        }
    }

    private Long snapshot(Long companyId, String endpoint, String requestKey, String json) {
        return jdbc.queryForObject("""
                INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
                VALUES (?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(company_id,endpoint,request_key,payload_hash)
                DO UPDATE SET fetched_at=now()
                RETURNING id
                """, Long.class, companyId, endpoint, requestKey, DartTransformer.sha256(json), json);
    }

    private void bindFact(PreparedStatement ps, Long companyId, Long rawId,
                          CollectedCompany collected, FinancialData row) throws SQLException {
        var period = collected.period();
        ps.setLong(1, companyId);
        ps.setInt(2, period.fiscalYear());
        ps.setString(3, period.reportType().name());
        ps.setString(4, period.statementType().name());
        ps.setString(5, row.statementDivision());
        ps.setString(6, row.accountKey());
        ps.setString(7, row.accountCode());
        ps.setString(8, row.standardAccount().name());
        ps.setString(9, row.rawAccountName());
        ps.setString(10, row.accountDetail());
        ps.setBigDecimal(11, row.amount());
        ps.setBigDecimal(12, row.cumulativeAmount());
        ps.setString(13, row.currency());
        ps.setString(14, row.receiptNumber());
        ps.setString(15, collected.source());
        ps.setLong(16, rawId);
    }
}
