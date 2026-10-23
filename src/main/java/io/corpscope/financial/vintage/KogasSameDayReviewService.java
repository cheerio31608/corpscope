package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Narrow decision for the 2022-03-25 2021 KOGAS main/attachment correction pair. */
@Service
public class KogasSameDayReviewService {
    private static final String CORP="00261285", FIRST="20220321001375";
    private static final String ATTACHMENT="20220325000207", MAIN="20220325000211";
    private static final LocalDate DATE=LocalDate.of(2022,3,25);
    private static final String PROFILE="KOGAS_2021_CFS_SAME_DAY_MAIN_ATTACHMENT_SIX_TOTALS_V1";
    private static final String FIRST_HASH="8c8102934a942338ca8c9adcc11825d86c0f1f061fca26f4fde4412691cdad90";
    private static final String MAIN_HASH="abcc60a785ca33a66cb531072b74e92ac2567c1cf43f9a8b361aeff351d47b28";
    private static final String VIEWER_HASH="5345a7e957c6f0308992862d969c4978a3e7fd51ca8f2ff681573ee9ea519195";
    private static final String CORRECTION_HASH="122af01c6062f5dbcb6a3860b9f0afc23b9b4b6d421177c82ae7caf4f36f1c2e";
    private final JdbcTemplate jdbc;
    private final KogasAnnualReportParser parser;
    private final ObjectMapper mapper;
    public KogasSameDayReviewService(JdbcTemplate jdbc,KogasAnnualReportParser parser,ObjectMapper mapper) {
        this.jdbc=jdbc;this.parser=parser;this.mapper=mapper;
    }

    @Transactional
    public Map<String,Object> review(Path evidenceDirectory) {
        if(evidenceDirectory==null || !Files.isDirectory(evidenceDirectory))
            throw new IllegalArgumentException("Official KOGAS viewer evidence directory is required");
        long company=onlyId("SELECT id FROM company WHERE corp_code=? FOR UPDATE",CORP);
        byte[] first=archive(company,FIRST,FIRST_HASH);
        byte[] main=archive(company,MAIN,MAIN_HASH);
        var firstParsed=parser.parse(FIRST,first).current();
        var mainParsed=parser.parse(MAIN,main).current();
        for(var account:firstParsed.accounts()) {
            var match=mainParsed.accounts().stream().filter(a -> a.standardAccount()==account.standardAccount()).toList();
            if(match.size()!=1 || match.getFirst().amount().compareTo(account.amount())!=0)
                throw new IllegalArgumentException("KOGAS original and written-correction six totals differ");
        }
        String viewer=verifiedFile(evidenceDirectory,"20220325000207-viewer.html",VIEWER_HASH);
        String correction=verifiedFile(evidenceDirectory,"20220325000207-correction-source.html",CORRECTION_HASH);
        var html=Jsoup.parse(viewer);
        Set<String> mains=new HashSet<>();
        html.select("#family option[value^=rcpNo]").forEach(e -> mains.add(e.attr("value")));
        if(!mains.equals(Set.of("rcpNo="+FIRST,"rcpNo="+MAIN)) ||
            html.select("#att option[value='rcpNo="+ATTACHMENT+"&dcmNo=8503492']").size()!=1)
            throw new IllegalArgumentException("KOGAS official viewer main/attachment relationship changed");
        String correctionText=Jsoup.parse(correction).text().replaceAll("[\\s\\u00a0\\u3000]+","");
        for(String required:List.of("정정대상공시서류:영업보고서","최초제출일:2022.3.21",
                "연결현금흐름표오류수정","(2,063,880,021,038)","(2,070,539,824,957)"))
            if(!correctionText.contains(required))
                throw new IllegalArgumentException("KOGAS attachment correction scope changed");
        var publications=jdbc.queryForList("""
            SELECT receipt_number,receipt_date,report_title FROM report_publication
            WHERE company_id=? AND receipt_number IN (?,?,?)
            """,company,FIRST,ATTACHMENT,MAIN);
        if(publications.size()!=3) throw new IllegalArgumentException("KOGAS publication list is incomplete");
        Map<String,Map<String,Object>> byReceipt=new HashMap<>();
        publications.forEach(row -> byReceipt.put((String)row.get("receipt_number"),row));
        assertPublication(byReceipt,FIRST,LocalDate.of(2022,3,21),"사업보고서 (2021.12)");
        assertPublication(byReceipt,ATTACHMENT,DATE,"[첨부정정]사업보고서 (2021.12)");
        assertPublication(byReceipt,MAIN,DATE,"[기재정정]사업보고서 (2021.12)");
        var sameDay=jdbc.queryForList("""
            SELECT receipt_number FROM report_publication WHERE company_id=? AND receipt_date=?
              AND report_title LIKE '%사업보고서 (2021.12)'
            """,String.class,company,DATE);
        if(!Set.copyOf(sameDay).equals(Set.of(ATTACHMENT,MAIN)) || sameDay.size()!=2)
            throw new IllegalArgumentException("Other KOGAS same-day filing requires review");
        long version=onlyId("""
            SELECT v.id FROM financial_report_version v JOIN report_document d
              ON d.company_id=v.company_id AND d.receipt_number=v.receipt_number
            WHERE v.company_id=? AND v.fiscal_year=2021 AND v.report_type='ANNUAL' AND v.statement_type='CFS'
              AND v.receipt_number=? AND v.extraction_profile='KOGAS_2021_ANNUAL_CFS_SIX_TOTALS_V1'
              AND d.sha256=?
            """,company,MAIN,MAIN_HASH);
        Integer totals=jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE version_id=?",Integer.class,version);
        if(totals==null || totals!=6) throw new IllegalArgumentException("KOGAS written-correction six totals missing");
        String evidence;
        try { evidence=mapper.writeValueAsString(Map.of("originalReceipt",FIRST,
            "writtenMainReceipt",MAIN,"attachmentReceipt",ATTACHMENT,
            "originalZipSha256",FIRST_HASH,"writtenMainZipSha256",MAIN_HASH,
            "officialViewerSha256",VIEWER_HASH,"attachmentCorrectionHtmlSha256",CORRECTION_HASH,
            "scope","ANNUAL_CFS_SIX_CURRENT_TOTALS_ONLY",
            "limitation","Same-day intraday order is unknown; both filings are treated as available from the next KST date")); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalArgumentException("Cannot serialize KOGAS review evidence");}
        var saved=jdbc.queryForList("""
            INSERT INTO financial_same_day_main_review(company_id,fiscal_year,report_type,statement_type,
              filing_date,selected_main_receipt_number,attachment_receipt_number,selected_main_sha256,review_profile,evidence)
            VALUES (?,2021,'ANNUAL','CFS',?,?,?,?,?,CAST(? AS jsonb))
            ON CONFLICT(company_id,fiscal_year,report_type,statement_type,filing_date)
            DO UPDATE SET selected_main_receipt_number=EXCLUDED.selected_main_receipt_number
            WHERE financial_same_day_main_review.attachment_receipt_number=EXCLUDED.attachment_receipt_number
              AND financial_same_day_main_review.selected_main_sha256=EXCLUDED.selected_main_sha256
              AND financial_same_day_main_review.review_profile=EXCLUDED.review_profile
              AND financial_same_day_main_review.evidence=EXCLUDED.evidence
            RETURNING selected_main_receipt_number
            """,String.class,company,DATE,MAIN,ATTACHMENT,MAIN_HASH,PROFILE,evidence);
        if(saved.size()!=1) throw new IllegalArgumentException("Existing KOGAS same-day review differs");
        return Map.of("reviewProfile",PROFILE,"selectedMainReceipt",MAIN,"attachmentReceipt",ATTACHMENT,
            "availableFromKstDate",DATE.plusDays(1),"scope","Annual CFS six current totals only",
            "forecastReady",false);
    }
    private long onlyId(String sql,Object... args) {
        var ids=jdbc.queryForList(sql,Long.class,args);
        if(ids.size()!=1) throw new IllegalArgumentException("KOGAS review requires one matching company or version");
        return ids.getFirst();
    }
    private byte[] archive(long company,String receipt,String hash) {
        var bytes=jdbc.queryForList("SELECT zip_bytes FROM report_document WHERE company_id=? AND receipt_number=? AND sha256=?",
            byte[].class,company,receipt,hash);
        if(bytes.size()!=1 || !ReportVersionCollectionService.sha256(bytes.getFirst()).equals(hash))
            throw new IllegalArgumentException("Official KOGAS archive missing or changed");
        return bytes.getFirst();
    }
    private static String verifiedFile(Path folder,String name,String hash) {
        try {
            byte[] bytes=Files.readAllBytes(folder.resolve(name));
            if(bytes.length>100_000 || !ReportVersionCollectionService.sha256(bytes).equals(hash))
                throw new IllegalArgumentException("Official KOGAS viewer evidence changed");
            // The saved viewer mixes character encodings; only its ASCII option IDs are used.
            return new String(bytes,name.endsWith("-viewer.html") ? StandardCharsets.ISO_8859_1:StandardCharsets.UTF_8);
        } catch(java.io.IOException ex) {throw new IllegalArgumentException("Official KOGAS viewer evidence missing");}
    }
    private static void assertPublication(Map<String,Map<String,Object>> rows,String receipt,LocalDate date,String title) {
        var row=rows.get(receipt);
        if(row==null || !date.equals(((java.sql.Date)row.get("receipt_date")).toLocalDate()) ||
            !title.equals(row.get("report_title")))
            throw new IllegalArgumentException("KOGAS receipt date or correction type changed");
    }
}
