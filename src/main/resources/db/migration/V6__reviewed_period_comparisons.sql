-- A review applies to exact report versions, never to a company/year unconditionally.
CREATE TABLE financial_period_comparison_review (
    current_version_id BIGINT NOT NULL REFERENCES financial_report_version(id),
    previous_version_id BIGINT NOT NULL REFERENCES financial_report_version(id),
    comparison_profile VARCHAR(150) NOT NULL,
    evidence_available_from_kst_date DATE NOT NULL,
    evidence JSONB NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(current_version_id,previous_version_id),
    CHECK(current_version_id<>previous_version_id)
);
