-- One exact non-standard CFS income total, verified against its source report.
UPDATE financial_fact f SET standard_account='NET_INCOME'
FROM company c
WHERE c.id=f.company_id AND c.corp_code='01390344'
  AND f.fiscal_year=2023 AND f.report_type='Q3' AND f.statement_type='CFS'
  AND f.statement_division='CIS' AND f.account_detail='-'
  AND f.account_code='-표준계정코드 미사용-' AND f.raw_account_name='분기순이익(손실)'
  AND f.standard_account='UNMAPPED' AND f.source='OPENDART';
