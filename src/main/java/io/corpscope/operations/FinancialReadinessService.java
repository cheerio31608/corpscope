package io.corpscope.operations;

import io.corpscope.analysis.AnalysisEligibilityProperties;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialReadinessService {
    private final JdbcTemplate jdbc;
    private final AnalysisEligibilityProperties eligibility;

    public FinancialReadinessService(JdbcTemplate jdbc, AnalysisEligibilityProperties eligibility) {
        this.jdbc = jdbc;
        this.eligibility = eligibility;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> review() {
        return Map.of(
                "accountCounts", jdbc.queryForList("""
                    SELECT account_status,count(*) AS count FROM main_account_audit
                    WHERE source='OPENDART' GROUP BY account_status ORDER BY account_status
                    """),
                "accountIssues", jdbc.queryForList("""
                    SELECT * FROM main_account_audit WHERE source='OPENDART' AND account_status<>'PRESENT'
                    ORDER BY corp_code,fiscal_year,standard_account
                    """),
                "publications", jdbc.queryForList("""
                    SELECT c.corp_code,c.name,p.* FROM financial_report_provenance p
                    JOIN company c ON c.id=p.company_id WHERE source='OPENDART'
                    ORDER BY c.corp_code,fiscal_year,receipt_number
                    """),
                "noDataSnapshots", jdbc.queryForList("""
                    SELECT c.corp_code,c.name,r.id AS raw_ingestion_id,r.request_key,r.fetched_at,
                      r.payload->>'status' AS dart_status
                    FROM raw_ingestion r JOIN company c ON c.id=r.company_id
                    WHERE r.endpoint='fnlttSinglAcntAll' AND r.payload->>'status'='013'
                    AND EXISTS (SELECT 1 FROM financial_fact f WHERE f.company_id=c.id AND f.source='OPENDART')
                    ORDER BY c.corp_code,r.request_key
                    """),
                "filingVersions", jdbc.queryForList("""
                    SELECT c.corp_code,p.receipt_number,p.receipt_date,p.report_title,p.correction_marker,
                      p.list_raw_ingestion_id,p.first_verified_at,
                      substring(p.report_title from '사업보고서[[:space:]]*[(]([0-9]{4}[.][0-9]{2})[)]') AS report_period_label
                    FROM report_publication p JOIN company c ON c.id=p.company_id
                    ORDER BY c.corp_code,p.receipt_date,p.receipt_number
                    """),
                "companyExclusions", eligibility.excludedCompanyReasons(),
                "periodExclusions", eligibility.excludedPeriodReasons(),
                "pointInTimeReady", false,
                "limitations", java.util.List.of("A filing date does not verify historical account values",
                    "Missing accounts are not zero; no-data status does not explain its cause",
                    "Comparability and metric exclusions still require the documented review"));
    }
}
