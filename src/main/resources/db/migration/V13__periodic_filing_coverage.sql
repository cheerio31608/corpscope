-- Verified complete list.json intervals for each exact fiscal period. A002 and A003
-- searches are different evidence even when date bounds happen to match.
CREATE TABLE periodic_filing_coverage (
    company_id BIGINT NOT NULL REFERENCES company(id),
    fiscal_year INTEGER NOT NULL CHECK (fiscal_year BETWEEN 2015 AND 2100),
    report_type VARCHAR(20) NOT NULL CHECK (report_type IN ('Q1','HALF_YEAR','Q3')),
    detail_type VARCHAR(4) NOT NULL CHECK (detail_type IN ('A002','A003')),
    checked_from DATE NOT NULL,
    checked_through DATE NOT NULL,
    total_pages INTEGER NOT NULL CHECK (total_pages BETWEEN 1 AND 100),
    total_count INTEGER NOT NULL CHECK (total_count >= 0),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(company_id,fiscal_year,report_type,checked_from,checked_through),
    CHECK (checked_through >= checked_from)
);
