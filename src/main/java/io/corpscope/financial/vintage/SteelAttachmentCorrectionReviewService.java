package io.corpscope.financial.vintage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One deliberately narrow review: Hyundai Steel's two 2025 attachment corrections. */
@Service
public class SteelAttachmentCorrectionReviewService {
    private static final String CORP="00145880", ORIGINAL="20260318001506";
    private static final String FIRST="20260319000771", SECOND="20260319000868";
    private static final LocalDate DATE=LocalDate.of(2026,3,19);
    private static final String PROFILE="STEEL_2025_CFS_ATTACHMENT_ONLY_V1";
    private static final String ORIGINAL_HASH="57c0c05b71c02f1501e62e3a7c733b8ed74e82888351932716181ab49c6f1920";
    private static final String FIRST_HASH="2e9a1dcf0e63f1136c842048bcccffe62077b80ec2095d33514614f621fa4562";
    private static final String VIEWER_HASH="66cee3a9d4550c64ebcc5ed7f923fb2136a648d4e99be39857d7f9c06cda25cb";
    private static final String FIRST_BUSINESS_HASH="d8e303f41a594b793d533821cc4e30893fd166888220711ad966efa66dbc3940";
    private static final String SECOND_BUSINESS_HASH="b85b9b652c4af4894cd34619aa8247fc97a7b207197b6d135a65929dba61a237";
    private static final Map<String,String> ATTACHMENTS=Map.of(
        FIRST+"&dcmNo=11149170","감사보고서",FIRST+"&dcmNo=11149171","연결감사보고서",
        FIRST+"&dcmNo=11149172","영업보고서",SECOND+"&dcmNo=11149845","영업보고서");
    private static final String[] BALANCE={"34,442,304,939,493","14,602,128,875,438","19,840,176,064,055"};
    private static final String[] INCOME={"22,733,237,259,727","219,235,156,230","1,389,012,026"};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SteelAttachmentCorrectionReviewService(JdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;this.mapper=mapper;
    }

    @Transactional
    public Map<String,Object> review(Path evidenceDirectory) {
        if(evidenceDirectory==null || !Files.isDirectory(evidenceDirectory))
            throw new IllegalArgumentException("Official DART evidence directory is required");
        var company=jdbc.queryForList("SELECT id FROM company WHERE corp_code=? FOR UPDATE",Long.class,CORP);
        if(company.size()!=1) throw new IllegalArgumentException("Hyundai Steel company missing");
        long id=company.getFirst();
        byte[] original=archive(id,ORIGINAL,ORIGINAL_HASH);
        byte[] first=archive(id,FIRST,FIRST_HASH);
        if(!zipEntries(original).equals(Set.of(ORIGINAL+".xml",ORIGINAL+"_00760.xml",ORIGINAL+"_00761.xml")) ||
            !zipEntries(first).equals(Set.of(FIRST+"_00760.xml",FIRST+"_00761.xml")))
            throw new IllegalArgumentException("Archived main/attachment ZIP layout changed");
        String viewer=verifiedFile(evidenceDirectory,"20260319000868-viewer.html",VIEWER_HASH);
        String firstBusiness=verifiedFile(evidenceDirectory,"20260319000771-business-source.html",FIRST_BUSINESS_HASH);
        String secondBusiness=verifiedFile(evidenceDirectory,"20260319000868-correction-source.html",SECOND_BUSINESS_HASH);
        verifyViewer(viewer);
        verifyBusinessCorrection(firstBusiness);
        verifyBusinessCorrection(secondBusiness);
        String oldAudit=zipEntry(original,ORIGINAL+"_00761.xml");
        String newAudit=zipEntry(first,FIRST+"_00761.xml");
        for(String[] totals:List.of(BALANCE,INCOME)) {
            String oldTable=financialTable(oldAudit,totals);
            String newTable=financialTable(newAudit,totals);
            if(!oldTable.equals(newTable)) throw new IllegalArgumentException("Corrected consolidated financial table changed");
        }
        var publications=jdbc.queryForList("""
            SELECT receipt_number,receipt_date,report_title FROM report_publication
            WHERE company_id=? AND receipt_number IN (?,?,?)
            """,id,ORIGINAL,FIRST,SECOND);
        if(publications.size()!=3) throw new IllegalArgumentException("Official filing list incomplete");
        Map<String,Map<String,Object>> byReceipt=new HashMap<>();
        for(var row:publications) byReceipt.put((String)row.get("receipt_number"),row);
        if(!LocalDate.of(2026,3,18).equals(((java.sql.Date)byReceipt.get(ORIGINAL).get("receipt_date")).toLocalDate()) ||
            !"사업보고서 (2025.12)".equals(byReceipt.get(ORIGINAL).get("report_title")))
            throw new IllegalArgumentException("Original publication changed");
        for(String receipt:List.of(FIRST,SECOND)) {
            var publication=byReceipt.get(receipt);
            if(!DATE.equals(((java.sql.Date)publication.get("receipt_date")).toLocalDate()) ||
                !"[첨부정정]사업보고서 (2025.12)".equals(publication.get("report_title")))
                throw new IllegalArgumentException("Attachment correction publication changed");
        }
        Integer total=jdbc.queryForObject("""
            SELECT count(*) FROM report_publication WHERE company_id=? AND receipt_date=?
              AND report_title LIKE '%사업보고서 (2025.12)'
            """,Integer.class,id,DATE);
        if(total==null || total!=2) throw new IllegalArgumentException("Other same-day correction requires review");
        Integer reviewed=jdbc.queryForObject("""
            SELECT count(*) FROM financial_report_version v JOIN report_document d
              ON d.company_id=v.company_id AND d.receipt_number=v.receipt_number
            WHERE v.company_id=? AND v.fiscal_year=2025 AND v.report_type='ANNUAL'
              AND v.statement_type='CFS' AND v.receipt_number=? AND d.sha256=?
              AND v.extraction_profile='STEEL_2025_ANNUAL_CFS_SIX_TOTALS_V1'
            """,Integer.class,id,ORIGINAL,ORIGINAL_HASH);
        if(reviewed==null || reviewed!=1) throw new IllegalArgumentException("Original six totals are not reviewed");
        Map<String,Object> evidence=Map.of("originalZipSha256",ORIGINAL_HASH,"firstCorrectionZipSha256",FIRST_HASH,
            "officialViewerSha256",VIEWER_HASH,"firstBusinessHtmlSha256",FIRST_BUSINESS_HASH,
            "secondBusinessHtmlSha256",SECOND_BUSINESS_HASH,"scope","MAIN_ANNUAL_CFS_SIX_TOTALS_UNCHANGED",
            "consolidatedBalanceTableSha256",sha(financialTable(oldAudit,BALANCE).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "consolidatedIncomeTableSha256",sha(financialTable(oldAudit,INCOME).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "limitation","Current official viewer and archived documents; exact intraday publication times unavailable");
        String json;
        try {json=mapper.writeValueAsString(evidence);}
        catch(JsonProcessingException ex) {throw new IllegalArgumentException("Cannot serialize review evidence");}
        for(String receipt:List.of(FIRST,SECOND)) {
            var inserted=jdbc.queryForList("""
                INSERT INTO financial_attachment_correction_review(company_id,fiscal_year,report_type,statement_type,
                  original_receipt_number,correction_receipt_number,correction_date,original_document_sha256,review_profile,evidence)
                VALUES (?,2025,'ANNUAL','CFS',?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(company_id,correction_receipt_number,statement_type)
                DO UPDATE SET correction_receipt_number=EXCLUDED.correction_receipt_number
                WHERE financial_attachment_correction_review.original_receipt_number=EXCLUDED.original_receipt_number
                  AND financial_attachment_correction_review.original_document_sha256=EXCLUDED.original_document_sha256
                  AND financial_attachment_correction_review.review_profile=EXCLUDED.review_profile
                  AND financial_attachment_correction_review.evidence=EXCLUDED.evidence
                RETURNING correction_receipt_number
                """,String.class,id,ORIGINAL,receipt,DATE,ORIGINAL_HASH,PROFILE,json);
            if(inserted.size()!=1) throw new IllegalArgumentException("Existing attachment review differs");
        }
        return Map.of("reviewProfile",PROFILE,"originalReceipt",ORIGINAL,"corrections",List.of(FIRST,SECOND),
            "effectiveFromKstDate",DATE.plusDays(1),"scope","Original main document CFS six totals only",
            "evidence",evidence);
    }

    private byte[] archive(long company,String receipt,String hash) {
        var bytes=jdbc.queryForList("SELECT zip_bytes FROM report_document WHERE company_id=? AND receipt_number=? AND sha256=?",
            byte[].class,company,receipt,hash);
        if(bytes.size()!=1 || !sha(bytes.getFirst()).equals(hash)) throw new IllegalArgumentException("Archived official ZIP missing or changed");
        return bytes.getFirst();
    }
    private static String verifiedFile(Path directory,String name,String expected) {
        try {
            byte[] bytes=Files.readAllBytes(directory.resolve(name));
            if(bytes.length>100_000 || !sha(bytes).equals(expected)) throw new IllegalArgumentException("Official viewer capture changed: "+name);
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        } catch(IOException ex) {throw new IllegalArgumentException("Official viewer capture missing: "+name);}
    }
    private static void verifyViewer(String html) {
        Document doc=Jsoup.parse(html);
        var mains=doc.select("#family option[value^=rcpNo]");
        if(mains.size()!=1 || !mains.getFirst().attr("value").equals("rcpNo="+ORIGINAL))
            throw new IllegalArgumentException("Official viewer main document changed");
        Map<String,String> corrections=new HashMap<>();
        for(var option:doc.select("#att option[value^=rcpNo]")) {
            String value=option.attr("value");
            if(value.startsWith("rcpNo="+FIRST) || value.startsWith("rcpNo="+SECOND))
                corrections.put(value.substring("rcpNo=".length()),option.text().replaceAll("\\s+",""));
        }
        if(!corrections.keySet().equals(ATTACHMENTS.keySet())) throw new IllegalArgumentException("Official viewer attachment set changed");
        for(var entry:ATTACHMENTS.entrySet())
            if(!corrections.get(entry.getKey()).contains("[정정]"+entry.getValue()))
                throw new IllegalArgumentException("Official viewer attachment type changed");
    }
    private static void verifyBusinessCorrection(String html) {
        String text=Jsoup.parse(html).text().replaceAll("\\s+","");
        for(String required:List.of("정정대상공시서류:영업보고서","최초제출일:2026년3월18일",
                "정정사유:재무제표감사보고서에병기된일자수정","2025년3월18일자감사보고서",
                "2026년3월18일자감사보고서","(별도재무제표)"))
            if(!text.contains(required)) throw new IllegalArgumentException("Business attachment correction scope changed");
    }
    private static String financialTable(String xml,String[] totals) {
        var matches=Jsoup.parse(xml,"",org.jsoup.parser.Parser.xmlParser()).getElementsByTag("TABLE").stream()
            .map(e -> e.text().replaceAll("[\\s\\u00a0\\u3000]+",""))
            .filter(text -> Arrays.stream(totals).allMatch(text::contains)).toList();
        if(matches.size()!=1) throw new IllegalArgumentException("Reviewed consolidated financial table missing or ambiguous");
        return matches.getFirst();
    }
    private static String zipEntry(byte[] zip,String expected) {
        try(var input=new ZipInputStream(new ByteArrayInputStream(zip))) {
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()) {
                if(entry.getName().equals(expected)) {
                    byte[] bytes=input.readNBytes(2_000_001);
                    if(bytes.length>2_000_000) throw new IllegalArgumentException("Audit attachment too large");
                    return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch(IOException ex) {throw new IllegalArgumentException("Cannot inspect archived attachment");}
        throw new IllegalArgumentException("Consolidated audit attachment missing");
    }
    private static Set<String> zipEntries(byte[] zip) {
        Set<String> names=new HashSet<>();
        try(var input=new ZipInputStream(new ByteArrayInputStream(zip))) {
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()) {
                if(entry.isDirectory() || !names.add(entry.getName()) || names.size()>10)
                    throw new IllegalArgumentException("Unexpected or duplicate ZIP entry");
            }
        } catch(IOException ex) {throw new IllegalArgumentException("Cannot inspect official ZIP layout");}
        return names;
    }
    private static String sha(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
}
