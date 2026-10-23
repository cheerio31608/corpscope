-- Keep the reviewed annual CFS versions intact; permit separately reviewed OFS versions.
-- The existing unique key already distinguishes statement_type.
ALTER TABLE financial_report_version
    DROP CONSTRAINT financial_report_version_statement_type_check;
ALTER TABLE financial_report_version
    ADD CONSTRAINT financial_report_version_statement_type_check
    CHECK (statement_type IN ('CFS','OFS'));
