-- A filing-list correction may replace an attachment without replacing the reviewed annual main document.
-- Record each explicitly reviewed correction; absence of a row always keeps the as-of lookup withheld.
CREATE TABLE financial_attachment_correction_review (
    company_id BIGINT NOT NULL REFERENCES company(id),
    fiscal_year INTEGER NOT NULL,
    report_type VARCHAR(16) NOT NULL,
    statement_type VARCHAR(8) NOT NULL,
    original_receipt_number VARCHAR(14) NOT NULL,
    correction_receipt_number VARCHAR(14) NOT NULL,
    correction_date DATE NOT NULL,
    original_document_sha256 VARCHAR(64) NOT NULL,
    review_profile VARCHAR(100) NOT NULL,
    evidence JSONB NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, correction_receipt_number, statement_type),
    CHECK (correction_receipt_number <> original_receipt_number)
);
