-- HD Hyundai Heavy Industries annual CFS explicitly displays thousand KRW.
-- Amount remains normalized to KRW in financial_version_fact.
ALTER TABLE financial_version_fact DROP CONSTRAINT financial_version_fact_display_unit_check;
ALTER TABLE financial_version_fact ADD CONSTRAINT financial_version_fact_display_unit_check
    CHECK (display_unit IN (1,1000,1000000));
