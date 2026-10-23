-- A same-day attachment correction and a complete written correction may share the
-- filing-list date. Preserve the explicit six-total selection decision and its evidence.
CREATE TABLE financial_same_day_main_review (
    company_id BIGINT NOT NULL REFERENCES company(id),
    fiscal_year INTEGER NOT NULL,
    report_type VARCHAR(16) NOT NULL,
    statement_type VARCHAR(8) NOT NULL,
    filing_date DATE NOT NULL,
    selected_main_receipt_number VARCHAR(14) NOT NULL,
    attachment_receipt_number VARCHAR(14) NOT NULL,
    selected_main_sha256 VARCHAR(64) NOT NULL,
    review_profile VARCHAR(100) NOT NULL,
    evidence JSONB NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, fiscal_year, report_type, statement_type, filing_date),
    CHECK (selected_main_receipt_number <> attachment_receipt_number)
);
