-- Metadata is evidence of a filing date, not proof of historical financial-version coverage.
CREATE TABLE report_publication (
    company_id BIGINT NOT NULL REFERENCES company(id),
    receipt_number VARCHAR(14) NOT NULL CHECK (receipt_number ~ '^[0-9]{14}$'),
    receipt_date DATE NOT NULL,
    report_title VARCHAR(500) NOT NULL,
    correction_marker BOOLEAN NOT NULL,
    list_raw_ingestion_id BIGINT NOT NULL REFERENCES raw_ingestion(id),
    first_verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(company_id, receipt_number)
);

CREATE VIEW financial_report_provenance AS
SELECT DISTINCT f.company_id, f.fiscal_year, f.report_type, f.statement_type, f.source,
       f.receipt_number, f.raw_ingestion_id, r.fetched_at AS snapshot_last_observed_at,
       p.receipt_date, p.report_title, p.correction_marker, p.list_raw_ingestion_id,
       CASE WHEN f.source <> 'OPENDART' THEN 'DEMO_NOT_VERIFIED'
            WHEN p.receipt_number IS NULL THEN 'UNVERIFIED' ELSE 'DATE_VERIFIED' END AS publication_status,
       -- list.json gives a date, not an intraday release time. Conservative KST next day boundary.
       CASE WHEN f.source = 'OPENDART' AND p.receipt_date IS NOT NULL
            THEN (p.receipt_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul' END AS usable_after_kst_day_end,
       'HISTORICAL_VALUES_NOT_VERIFIED'::text AS point_in_time_status
FROM financial_fact f JOIN raw_ingestion r ON r.id=f.raw_ingestion_id
LEFT JOIN report_publication p ON p.company_id=f.company_id AND p.receipt_number=f.receipt_number;

CREATE VIEW main_account_audit AS
WITH scopes AS (
    SELECT DISTINCT company_id,fiscal_year,report_type,statement_type,source FROM financial_fact
), accounts(standard_account) AS (
    VALUES ('REVENUE'),('OPERATING_PROFIT'),('NET_INCOME'),('ASSETS'),('LIABILITIES'),('EQUITY')
), candidates AS (
    SELECT f.*, CASE WHEN statement_division='IS' THEN 0 ELSE 1 END AS preference
    FROM financial_fact f WHERE (btrim(account_detail)='' OR account_detail='-')
      AND ((standard_account IN ('ASSETS','LIABILITIES','EQUITY') AND statement_division='BS')
        OR (standard_account IN ('REVENUE','OPERATING_PROFIT','NET_INCOME') AND statement_division IN ('IS','CIS')))
), preferred AS (
    SELECT *,min(preference) OVER (PARTITION BY company_id,fiscal_year,report_type,statement_type,source,standard_account) AS best
    FROM candidates
)
SELECT c.corp_code,c.name,s.fiscal_year,s.report_type,s.statement_type,s.source,a.standard_account,
       count(t.id) AS candidate_count,count(t.amount) AS amount_count,
       CASE WHEN count(t.id)=0 THEN 'MISSING_MAPPING_OR_ACCOUNT'
            WHEN count(t.id)>1 THEN 'AMBIGUOUS'
            WHEN count(t.amount)=0 THEN 'MISSING_AMOUNT' ELSE 'PRESENT' END AS account_status
FROM scopes s CROSS JOIN accounts a JOIN company c ON c.id=s.company_id
LEFT JOIN preferred t ON t.company_id=s.company_id AND t.fiscal_year=s.fiscal_year
    AND t.report_type=s.report_type AND t.statement_type=s.statement_type AND t.source=s.source
    AND t.standard_account=a.standard_account AND t.preference=t.best
GROUP BY c.corp_code,c.name,s.fiscal_year,s.report_type,s.statement_type,s.source,a.standard_account;
