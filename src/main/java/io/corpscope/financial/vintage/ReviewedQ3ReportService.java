package io.corpscope.financial.vintage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.common.NotFoundException;
import io.corpscope.financial.StandardAccount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Saves only the nine explicit 2025 Q3 CFS source profiles after ZIP/API equality checks. */
@Service
public class ReviewedQ3ReportService {
    private final JdbcTemplate jdbc;
    private final ReviewedQ3ReportParser parser;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public ReviewedQ3ReportService(JdbcTemplate jdbc,ReviewedQ3ReportParser parser,ObjectMapper mapper,
                                   PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.parser=parser;this.mapper=mapper;this.transaction=new TransactionTemplate(manager);
    }

    public Map<String,Object> review(String corpCode) {
        var profile=ReviewedQ3Profiles.BY_CORP.get(corpCode);
        if(profile==null) throw new IllegalArgumentException("Company is outside reviewed 2025 Q3 profiles");
        return transaction.execute(status -> {
            var companies=jdbc.queryForList("SELECT id FROM company WHERE corp_code=? FOR UPDATE",Long.class,corpCode);
            if(companies.size()!=1) throw new NotFoundException("Collect exact company first");
            long company=companies.getFirst();
            var coverage=jdbc.queryForList("""
                SELECT checked_through FROM periodic_filing_coverage
                WHERE company_id=? AND fiscal_year=2025 AND report_type='Q3' AND detail_type='A003'
                  AND checked_from<='2025-09-30' AND checked_through>='2025-12-31'
                """,LocalDate.class,company);
            if(coverage.isEmpty()) throw new IllegalArgumentException("Complete official Q3 filing coverage is required");
            String receipt=profile.receipt();
            var publications=jdbc.queryForList("""
                SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?
                """,company,receipt);
            if(publications.size()!=1 || !LocalDate.of(2025,11,14).equals(
                ((java.sql.Date)publications.getFirst().get("receipt_date")).toLocalDate())
                || !"분기보고서 (2025.09)".equals(publications.getFirst().get("report_title")))
                throw new IllegalArgumentException("Exact official Q3 publication has not been verified");
            var documents=jdbc.queryForList("SELECT sha256,zip_bytes FROM report_document WHERE company_id=? AND receipt_number=?",
                company,receipt);
            if(documents.size()!=1) throw new NotFoundException("Preserve exact official Q3 ZIP first");
            byte[] zip=(byte[])documents.getFirst().get("zip_bytes");
            String hash=ReportVersionCollectionService.sha256(zip);
            if(!hash.equals(documents.getFirst().get("sha256"))) throw new IllegalArgumentException("Stored ZIP hash mismatch");
            var parsed=parser.parse(corpCode,receipt,zip);
            var raw=jdbc.queryForList("""
                SELECT DISTINCT r.id,r.payload_hash FROM financial_fact f JOIN raw_ingestion r ON r.id=f.raw_ingestion_id
                WHERE f.company_id=? AND f.fiscal_year=2025 AND f.report_type='Q3' AND f.statement_type='CFS'
                  AND f.source='OPENDART' AND f.receipt_number=?
                """,company,receipt);
            if(raw.size()!=1) throw new IllegalArgumentException("One matching financial API snapshot is required");
            long rawId=((Number)raw.getFirst().get("id")).longValue();
            for(var account:parsed.accounts()) {
                var facts=jdbc.queryForList("""
                    SELECT amount,cumulative_amount,currency,account_code FROM financial_fact
                    WHERE company_id=? AND fiscal_year=2025 AND report_type='Q3' AND statement_type='CFS'
                      AND source='OPENDART' AND receipt_number=? AND raw_ingestion_id=?
                      AND statement_division=? AND standard_account=? AND account_detail='-'
                    """,company,receipt,rawId,account.statementDivision(),account.standardAccount().name());
                if(facts.size()!=1) throw new IllegalArgumentException("Missing or duplicate API account: "+account.standardAccount());
                var fact=facts.getFirst();
                BigDecimal stored=(BigDecimal)fact.get("amount");
                BigDecimal cumulative=(BigDecimal)fact.get("cumulative_amount");
                if(!"KRW".equals(fact.get("currency")) || !code(account.standardAccount()).equals(fact.get("account_code"))
                    || stored==null || stored.compareTo(account.amount())!=0
                    || (account.cumulativeAmount()==null ? cumulative!=null
                        : cumulative==null || cumulative.compareTo(account.cumulativeAmount())!=0))
                    throw new IllegalArgumentException("Official ZIP and stored API account differ: "+account.standardAccount());
            }
            Map<String,Object> evidence=new LinkedHashMap<>(parsed.evidence());
            evidence.put("apiPayloadSha256",raw.getFirst().get("payload_hash"));
            evidence.put("documentSha256",hash);
            String json;
            try {json=mapper.writeValueAsString(evidence);}
            catch(JsonProcessingException ex) {throw new IllegalArgumentException("Cannot serialize reviewed Q3 evidence");}
            var versions=jdbc.queryForList("""
                INSERT INTO quarterly_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
                  main_entry,document_encoding,extraction_profile,evidence)
                VALUES (?,2025,'Q3','CFS',?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(company_id,fiscal_year,report_type,statement_type,receipt_number)
                DO UPDATE SET receipt_number=EXCLUDED.receipt_number
                WHERE quarterly_report_version.main_entry=EXCLUDED.main_entry
                  AND quarterly_report_version.document_encoding=EXCLUDED.document_encoding
                  AND quarterly_report_version.extraction_profile=EXCLUDED.extraction_profile
                  AND quarterly_report_version.evidence=EXCLUDED.evidence RETURNING id
                """,Long.class,company,receipt,parsed.mainEntry(),parsed.encoding(),parsed.profile(),json);
            if(versions.size()!=1) throw new IllegalArgumentException("Previously reviewed Q3 extraction changed");
            long version=versions.getFirst();
            for(var account:parsed.accounts()) {
                jdbc.update("""
                    INSERT INTO quarterly_version_fact(version_id,standard_account,raw_account_name,statement_division,
                      amount,cumulative_amount,currency,display_unit) VALUES (?,?,?,?,?,?,'KRW',?)
                    ON CONFLICT(version_id,standard_account) DO NOTHING
                    """,version,account.standardAccount().name(),account.rawAccountName(),account.statementDivision(),
                    account.amount(),account.cumulativeAmount(),account.displayUnit());
                Integer matched=jdbc.queryForObject("""
                    SELECT count(*) FROM quarterly_version_fact WHERE version_id=? AND standard_account=?
                      AND raw_account_name=? AND statement_division=? AND amount=?
                      AND cumulative_amount IS NOT DISTINCT FROM ? AND currency='KRW' AND display_unit=?
                    """,Integer.class,version,account.standardAccount().name(),account.rawAccountName(),
                    account.statementDivision(),account.amount(),account.cumulativeAmount(),account.displayUnit());
                if(matched==null || matched!=1) throw new IllegalArgumentException("Stored Q3 account changed");
            }
            if(!Integer.valueOf(6).equals(jdbc.queryForObject(
                "SELECT count(*) FROM quarterly_version_fact WHERE version_id=?",Integer.class,version)))
                throw new IllegalArgumentException("Incomplete Q3 version");
            return Map.of("status","SIX_TOTALS_REVIEWED_NOT_MODEL_APPROVED","corpCode",corpCode,
                "receiptNumber",receipt,"versionId",version,"accounts",6,"displayUnit",profile.displayUnit(),
                "incomeDivision",profile.incomeDivision(),"documentSha256",hash,"apiPayloadSha256",raw.getFirst().get("payload_hash"));
        });
    }
    private static String code(StandardAccount account) {
        return switch(account) {
            case ASSETS -> "ifrs-full_Assets";case LIABILITIES -> "ifrs-full_Liabilities";
            case EQUITY -> "ifrs-full_Equity";case REVENUE -> "ifrs-full_Revenue";
            case OPERATING_PROFIT -> "dart_OperatingIncomeLoss";case NET_INCOME -> "ifrs-full_ProfitLoss";
            default -> throw new IllegalArgumentException("Unsupported reviewed Q3 account");
        };
    }
}
