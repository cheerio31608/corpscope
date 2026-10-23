package io.corpscope.financial.vintage;

import io.corpscope.common.NotFoundException;
import io.corpscope.financial.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static io.corpscope.financial.vintage.FinancialAsOfUnavailableException.Reason.*;

@Service
public class FinancialAsOfService {
    private final JdbcTemplate jdbc;
    public FinancialAsOfService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** asOf is the start of a KST date. Same-day filings are deliberately unavailable. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public FinancialAsOfResponse find(long companyId,int year,ReportType report,StatementType statement,LocalDate asOf) {
        if (asOf==null || year<2015 || year>2100 || report!=ReportType.ANNUAL) {
            throw new IllegalArgumentException("As-of lookup currently supports annual reports only");
        }
        var codes=jdbc.queryForList("SELECT corp_code FROM company WHERE id=?",String.class,companyId);
        if (codes.isEmpty()) throw new NotFoundException("Company not found");
        var checked=jdbc.queryForObject("""
            SELECT max(split_part(request_key,':',2)) FROM raw_ingestion
            WHERE company_id=? AND endpoint='list' AND payload->>'status'='000'
              AND request_key ~ '^20160101:[0-9]{8}:A001:N:[0-9]+$'
            """,String.class,companyId);
        if (checked==null) throw new FinancialAsOfUnavailableException(NO_FILING_COVERAGE,"Official filing search coverage is unavailable");
        LocalDate through=LocalDate.parse(checked,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        if (asOf.isAfter(through.plusDays(1))) throw new FinancialAsOfUnavailableException(OUTSIDE_FILING_COVERAGE,"Refresh official filing metadata for the requested date");
        // Choose from official filings BEFORE joining verified values: never silently fall back past an unverified correction.
        var candidates=jdbc.queryForList("""
            SELECT receipt_number,receipt_date,report_title FROM report_publication
            WHERE company_id=? AND substring(report_title from '사업보고서[[:space:]]*[(]([0-9]{4}[.][0-9]{2})[)]')=?
              AND receipt_date<?
            ORDER BY receipt_date DESC LIMIT 2
            """,companyId,year+".12",asOf);
        if (candidates.isEmpty()) throw new FinancialAsOfUnavailableException(NO_PUBLIC_FILING,"No annual filing was available before the requested KST date");
        var latest=candidates.getFirst();
        LocalDate date=((java.sql.Date)latest.get("receipt_date")).toLocalDate();
        boolean reviewedAttachmentCorrection=false;
        boolean reviewedSameDayMain=false;
        if (candidates.size()>1 && date.equals(((java.sql.Date)candidates.get(1).get("receipt_date")).toLocalDate())) {
            String main=reviewedSameDayMain(companyId,year,report,statement,date);
            if(main!=null) {
                var selected=jdbc.queryForList("""
                    SELECT receipt_number,receipt_date,report_title FROM report_publication
                    WHERE company_id=? AND receipt_number=? AND receipt_date=? AND report_title=?
                    """,companyId,main,date,"[기재정정]사업보고서 ("+year+".12)");
                if(selected.size()!=1) throw new FinancialAsOfUnavailableException(AMBIGUOUS_PUBLICATION_DAY,
                    "Reviewed same-day main publication is unavailable");
                latest=selected.getFirst();
                reviewedSameDayMain=true;
            } else {
                String original=reviewedAttachmentParent(companyId,year,report,statement,date);
                if(original==null) throw new FinancialAsOfUnavailableException(AMBIGUOUS_PUBLICATION_DAY,
                    "Multiple filings on the latest date require explicit version-order review");
                var originalPublication=jdbc.queryForList("""
                    SELECT receipt_number,receipt_date,report_title FROM report_publication
                    WHERE company_id=? AND receipt_number=? AND receipt_date<? AND report_title=?
                    """,companyId,original,date,"사업보고서 ("+year+".12)");
                if(originalPublication.size()!=1) throw new FinancialAsOfUnavailableException(AMBIGUOUS_PUBLICATION_DAY,
                    "Reviewed attachment parent publication is unavailable");
                latest=originalPublication.getFirst();
                date=((java.sql.Date)latest.get("receipt_date")).toLocalDate();
                reviewedAttachmentCorrection=true;
            }
        }
        String receipt=(String)latest.get("receipt_number");
        var versions=jdbc.queryForList("""
            SELECT v.id,v.extraction_profile,d.sha256 FROM financial_report_version v
            JOIN report_document d ON d.company_id=v.company_id AND d.receipt_number=v.receipt_number
            WHERE v.company_id=? AND v.fiscal_year=? AND v.report_type=? AND v.statement_type=? AND v.receipt_number=?
            """,companyId,year,report.name(),statement.name(),receipt);
        if (versions.size()!=1) throw new FinancialAsOfUnavailableException(UNREVIEWED_SELECTED_FILING,"Latest known filing totals have not been reviewed; no older-version fallback");
        var version=versions.getFirst();
        var accounts=jdbc.query("""
            SELECT standard_account,raw_account_name,statement_division,amount,currency,display_unit
            FROM financial_version_fact WHERE version_id=? ORDER BY standard_account
            """,(rs,n) -> new FinancialAsOfResponse.Account(StandardAccount.valueOf(rs.getString(1)),rs.getString(2),
                rs.getString(3),rs.getBigDecimal(4),rs.getString(5),rs.getLong(6)),version.get("id"));
        if (accounts.size()!=6) throw new FinancialAsOfUnavailableException(INCOMPLETE_REVIEWED_VALUES,"Reviewed version totals are incomplete");
        List<String> warnings=new java.util.ArrayList<>(List.of("SIX_REVIEWED_CURRENT_TOTALS_ONLY",
            "AS_OF_MEANS_START_OF_KST_DATE","HISTORICAL_OBSERVATION_NOT_CURRENT_AUDIT_VALIDITY",
            "CROSS_PERIOD_COMPARABILITY_NOT_VERIFIED"));
        if(reviewedAttachmentCorrection) warnings.add("REVIEWED_ATTACHMENT_CORRECTIONS_ORIGINAL_MAIN_SIX_TOTALS_UNCHANGED");
        if(reviewedSameDayMain) warnings.add("REVIEWED_SAME_DAY_MAIN_ATTACHMENT_SIX_TOTALS_ONLY");
        return new FinancialAsOfResponse(companyId,codes.getFirst(),year,report,statement,asOf,through,
            receipt,date,date.plusDays(1),(String)latest.get("report_title"),"OPENDART_DOCUMENT",
            (String)version.get("sha256"),(String)version.get("extraction_profile"),List.copyOf(accounts),
            List.copyOf(warnings));
    }

    private String reviewedSameDayMain(long companyId,int year,ReportType report,StatementType statement,LocalDate date) {
        if(year!=2021 || report!=ReportType.ANNUAL || statement!=StatementType.CFS ||
            !date.equals(LocalDate.of(2022,3,25))) return null;
        var corp=jdbc.queryForList("SELECT corp_code FROM company WHERE id=?",String.class,companyId);
        if(corp.size()!=1 || !corp.getFirst().equals("00261285")) return null;
        var sameDay=jdbc.queryForList("""
            SELECT receipt_number FROM report_publication WHERE company_id=? AND receipt_date=?
              AND report_title LIKE '%사업보고서 (2021.12)'
            """,String.class,companyId,date);
        if(sameDay.size()!=2 || !Set.copyOf(sameDay).equals(Set.of("20220325000207","20220325000211"))) return null;
        var reviewed=jdbc.queryForList("""
            SELECT r.selected_main_receipt_number FROM financial_same_day_main_review r
            JOIN report_document d ON d.company_id=r.company_id AND d.receipt_number=r.selected_main_receipt_number
            JOIN financial_report_version v ON v.company_id=r.company_id AND v.receipt_number=r.selected_main_receipt_number
              AND v.fiscal_year=2021 AND v.report_type='ANNUAL' AND v.statement_type='CFS'
            WHERE r.company_id=? AND r.fiscal_year=2021 AND r.report_type='ANNUAL' AND r.statement_type='CFS'
              AND r.filing_date=? AND r.selected_main_receipt_number='20220325000211'
              AND r.attachment_receipt_number='20220325000207'
              AND r.review_profile='KOGAS_2021_CFS_SAME_DAY_MAIN_ATTACHMENT_SIX_TOTALS_V1'
              AND d.sha256=r.selected_main_sha256
              AND v.extraction_profile='KOGAS_2021_ANNUAL_CFS_SIX_TOTALS_V1'
              AND (SELECT count(*) FROM financial_version_fact f WHERE f.version_id=v.id)=6
            """,String.class,companyId,date);
        return reviewed.size()==1 ? reviewed.getFirst():null;
    }

    private String reviewedAttachmentParent(long companyId,int year,ReportType report,StatementType statement,LocalDate date) {
        // Only the two source-reviewed Hyundai Steel 2025 CFS attachment corrections have this policy.
        if(year!=2025 || report!=ReportType.ANNUAL || statement!=StatementType.CFS ||
            !date.equals(LocalDate.of(2026,3,19))) return null;
        var sameDay=jdbc.queryForList("""
            SELECT receipt_number,report_title FROM report_publication
            WHERE company_id=? AND receipt_date=?
              AND substring(report_title from '사업보고서[[:space:]]*[(]([0-9]{4}[.][0-9]{2})[)]')=?
            """,companyId,date,year+".12");
        if(sameDay.size()<2 || sameDay.stream().anyMatch(row ->
            !row.get("report_title").equals("[첨부정정]사업보고서 ("+year+".12)"))) return null;
        var reviewed=jdbc.queryForList("""
            SELECT r.original_receipt_number,r.correction_receipt_number,r.original_document_sha256,
                   d.sha256 AS stored_original_hash
            FROM financial_attachment_correction_review r
            JOIN company c ON c.id=r.company_id AND c.corp_code='00145880'
            JOIN report_document d ON d.company_id=r.company_id AND d.receipt_number=r.original_receipt_number
            JOIN financial_report_version v ON v.company_id=r.company_id AND v.receipt_number=r.original_receipt_number
              AND v.fiscal_year=2025 AND v.report_type='ANNUAL' AND v.statement_type='CFS'
              AND v.extraction_profile='STEEL_2025_ANNUAL_CFS_SIX_TOTALS_V1'
            WHERE r.company_id=? AND r.fiscal_year=? AND r.report_type=? AND r.statement_type=?
              AND r.correction_date=? AND r.original_receipt_number='20260318001506'
              AND r.review_profile='STEEL_2025_CFS_ATTACHMENT_ONLY_V1'
            """,companyId,year,report.name(),statement.name(),date);
        if(reviewed.size()!=sameDay.size()) return null;
        java.util.Set<String> receipts=new java.util.HashSet<>();
        for(var row:sameDay) receipts.add((String)row.get("receipt_number"));
        if(!receipts.equals(java.util.Set.of("20260319000771","20260319000868"))) return null;
        String original=null;
        for(var row:reviewed) {
            if(!receipts.remove(row.get("correction_receipt_number")) ||
                !row.get("original_document_sha256").equals(row.get("stored_original_hash"))) return null;
            String parent=(String)row.get("original_receipt_number");
            if(original!=null && !original.equals(parent)) return null;
            original=parent;
        }
        return receipts.isEmpty() ? original : null;
    }
}
