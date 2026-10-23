-- Source-verified 2025 Q3 reports use won, thousand won, or million won.
-- Some issuers place the three-month profit totals in a combined CIS table.
ALTER TABLE quarterly_version_fact DROP CONSTRAINT quarterly_version_fact_display_unit_check;
ALTER TABLE quarterly_version_fact ADD CONSTRAINT quarterly_version_fact_display_unit_check
    CHECK (display_unit IN (1,1000,1000000));

ALTER TABLE quarterly_version_fact DROP CONSTRAINT quarterly_version_fact_statement_division_check;
ALTER TABLE quarterly_version_fact ADD CONSTRAINT quarterly_version_fact_statement_division_check
    CHECK (statement_division IN ('BS','IS','CIS'));

ALTER TABLE quarterly_version_fact DROP CONSTRAINT quarterly_version_fact_check;
ALTER TABLE quarterly_version_fact ADD CONSTRAINT quarterly_version_fact_check
    CHECK ((statement_division='BS' AND cumulative_amount IS NULL
            AND standard_account IN ('ASSETS','LIABILITIES','EQUITY'))
        OR (statement_division IN ('IS','CIS') AND cumulative_amount IS NOT NULL
            AND standard_account IN ('REVENUE','OPERATING_PROFIT','NET_INCOME')));
