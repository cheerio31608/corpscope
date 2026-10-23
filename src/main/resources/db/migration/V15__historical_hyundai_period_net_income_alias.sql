-- Exact non-standard connected net-income totals in 2023 Hyundai Motor interim reports.
-- Original account name/code, amount, receipt and raw JSON remain unchanged.
UPDATE financial_fact f SET standard_account='NET_INCOME'
FROM company c
WHERE c.id=f.company_id AND c.corp_code='00164742'
  AND f.fiscal_year=2023 AND f.report_type IN ('Q1','HALF_YEAR','Q3')
  AND f.statement_type='CFS' AND f.statement_division IN ('IS','CIS')
  AND f.account_code='-표준계정코드 미사용-' AND f.account_detail='-'
  AND f.raw_account_name IN ('연결분기순이익','연결반기순이익')
  AND f.standard_account='UNMAPPED' AND f.source='OPENDART';
