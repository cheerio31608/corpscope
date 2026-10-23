package io.corpscope.financial.vintage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.common.NotFoundException;
import io.corpscope.financial.StatementType;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic writer shared by the explicit review collectors. No dynamic mapping framework. */
final class ReviewedReportStore {
    record Input(int year,StatementType statement,String receipt,LocalDate date,byte[] zip,ReviewedReport report) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    ReviewedReportStore(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.jdbc=jdbc; this.mapper=mapper; this.transaction=new TransactionTemplate(transactions);
    }
    List<Map<String,Object>> save(String corpCode,List<Input> inputs) {
        if(inputs.isEmpty() || inputs.size()>5) throw new IllegalArgumentException("Review write requires 1..5 versions");
        inputs.forEach(input -> input.report().displayUnit());
        return transaction.execute(status -> {
            var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=? FOR UPDATE",Long.class,corpCode);
            if(ids.isEmpty()) throw new NotFoundException("Collect company and official filing metadata first");
            long company=ids.getFirst();
            List<Map<String,Object>> result=new ArrayList<>();
            for(var input:inputs) {
                var pub=jdbc.queryForList("SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?",company,input.receipt());
                if(pub.size()!=1 || !input.date().equals(((java.sql.Date)pub.getFirst().get("receipt_date")).toLocalDate()) ||
                    !((String)pub.getFirst().get("report_title")).endsWith("사업보고서 ("+input.year()+".12)"))
                    throw new IllegalArgumentException("Official receipt date or annual period has not been verified");
                String hash=ReportVersionCollectionService.sha256(input.zip());
                var previous=jdbc.queryForList("SELECT sha256 FROM report_document WHERE company_id=? AND receipt_number=?",String.class,company,input.receipt());
                if(!previous.isEmpty() && !hash.equals(previous.getFirst())) throw new IllegalArgumentException("Previously stored official ZIP changed; manual review required");
                jdbc.update("""
                    INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)
                    ON CONFLICT(company_id,receipt_number) DO NOTHING
                    """,company,input.receipt(),hash,input.zip());
                ReviewedReport report=input.report();
                String evidence=json(report.evidence());
                var versions=jdbc.queryForList("""
                    INSERT INTO financial_report_version(company_id,fiscal_year,report_type,statement_type,
                      receipt_number,main_entry,document_encoding,extraction_profile,evidence)
                    VALUES (?,?,'ANNUAL',?,?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT(company_id,fiscal_year,report_type,statement_type,receipt_number)
                    DO UPDATE SET receipt_number=EXCLUDED.receipt_number
                    WHERE financial_report_version.extraction_profile=EXCLUDED.extraction_profile
                      AND financial_report_version.evidence=EXCLUDED.evidence RETURNING id
                    """,Long.class,company,input.year(),input.statement().name(),input.receipt(),report.mainEntry(),report.encoding(),report.profile(),evidence);
                if(versions.size()!=1) throw new IllegalArgumentException("Reviewed extraction changed; manual review required");
                long version=versions.getFirst();
                for(var account:report.accounts()) {
                    jdbc.update("""
                        INSERT INTO financial_version_fact(version_id,standard_account,raw_account_name,
                          statement_division,amount,currency,display_unit) VALUES (?,?,?,?,?,'KRW',?)
                        ON CONFLICT(version_id,standard_account) DO NOTHING
                        """,version,account.standardAccount().name(),account.rawAccountName(),account.statementDivision(),account.amount(),report.displayUnit());
                    int matched=Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT count(*) FROM financial_version_fact WHERE version_id=? AND standard_account=?
                          AND raw_account_name=? AND statement_division=? AND amount=? AND currency='KRW' AND display_unit=?
                        """,Integer.class,version,account.standardAccount().name(),account.rawAccountName(),account.statementDivision(),account.amount(),report.displayUnit()));
                    if(matched!=1) throw new IllegalArgumentException("Stored version account differs from source");
                }
                if(!Integer.valueOf(6).equals(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE version_id=?",Integer.class,version)))
                    throw new IllegalArgumentException("Incomplete version accounts");
                result.add(Map.of("receiptNumber",input.receipt(),"versionId",version,"sha256",hash,
                    "receiptDate",input.date(),"statementType",input.statement(),"accounts",report.accounts()));
            }
            return List.copyOf(result);
        });
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalArgumentException("Cannot serialize reviewed evidence"); }
    }
}
