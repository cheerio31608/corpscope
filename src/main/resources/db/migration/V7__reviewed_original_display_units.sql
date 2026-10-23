-- Preserve original reporting scale separately from normalized KRW amounts.
-- Existing million-KRW rows are unchanged; reviewed NAVER reports use KRW directly.
ALTER TABLE financial_version_fact DROP CONSTRAINT financial_version_fact_display_unit_check;
ALTER TABLE financial_version_fact ADD CONSTRAINT financial_version_fact_display_unit_check
    CHECK (display_unit IN (1,1000000));
