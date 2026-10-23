package io.corpscope.financial.vintage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DartTransformer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Source cells without XBRL account/context: exact receipt, hash, table, label and column only. */
@Service
public class ReviewedPlainPeriodService {
    public record Row(String account,String label,String division,String group,long displayUnit,
                      BigDecimal amount,BigDecimal cumulative,int sourceCells,
                      String sourceCurrent,String sourceYtd,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String sourceCurrentCode,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String sourceCurrentContext,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String sourceYtdCode,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String sourceYtdContext) {}
    public record Profile(String corpCode,int fiscalYear,String reportType,String receiptNumber,
                          String documentSha256,List<Row> rows) {}
    public record Manifest(List<Profile> profiles) {}
    private final Map<String,Profile> profiles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public ReviewedPlainPeriodService(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.mapper=mapper;this.transaction=new TransactionTemplate(manager);
        try {
            Map<String,Profile> indexed=new HashMap<>();
            for(String resource:List.of("reviewed-plain-period-rows.json","reviewed-2023-old-rows.json")) {
                try(var in=new ClassPathResource(resource).getInputStream()) {
                    Manifest manifest=mapper.readValue(in,Manifest.class);
                    for(Profile p:manifest.profiles()) {
                        if(p.rows()==null || p.rows().size()!=6 || !p.documentSha256().matches("[0-9a-f]{64}")
                            || indexed.put(key(p.corpCode(),p.fiscalYear(),p.reportType()),p)!=null)
                            throw new IllegalArgumentException();
                    }
                }
            }
            if(indexed.size()!=62) throw new IllegalArgumentException();
            profiles=Map.copyOf(indexed);
        } catch(Exception ex) {throw new IllegalStateException("Invalid exact plain-row manifest",ex);}
    }

    public Map<String,Object> review(String corpCode,int year,String reportType) {
        Profile profile=profiles.get(key(corpCode,year,reportType));
        if(profile==null) throw new IllegalArgumentException("No exact source-row review profile");
        return transaction.execute(status -> {
            var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=? FOR UPDATE",Long.class,corpCode);
            if(ids.size()!=1) throw new IllegalArgumentException("Company missing");
            long company=ids.getFirst();
            int month=switch(reportType) {case "Q1" -> 3;case "HALF_YEAR" -> 6;case "Q3" -> 9;
                default -> throw new IllegalArgumentException("Quarterly report required");};
            LocalDate periodEnd=LocalDate.of(year,month,1).withDayOfMonth(
                LocalDate.of(year,month,1).lengthOfMonth());
            var publications=jdbc.queryForList("""
                SELECT receipt_date,report_title,correction_marker FROM report_publication
                WHERE company_id=? AND receipt_number=?
                """,company,profile.receiptNumber());
            String title=(reportType.equals("HALF_YEAR")?"반기보고서 (":"분기보고서 (")
                +year+"."+String.format(Locale.ROOT,"%02d",month)+")";
            if(publications.size()!=1)
                throw new IllegalArgumentException("Exact official source publication missing");
            String officialTitle=(String)publications.getFirst().get("report_title");
            if(!(title.equals(officialTitle) ||
                (("[기재정정]"+title).equals(officialTitle) &&
                    Boolean.TRUE.equals(publications.getFirst().get("correction_marker")))))
                throw new IllegalArgumentException("Exact official source publication missing");
            LocalDate date=((java.sql.Date)publications.getFirst().get("receipt_date")).toLocalDate();
            Integer coverage=jdbc.queryForObject("""
                SELECT count(*) FROM periodic_filing_coverage WHERE company_id=? AND fiscal_year=?
                  AND report_type=? AND checked_from<=? AND checked_through>=?
                """,Integer.class,company,year,reportType,periodEnd,date);
            if(coverage==null || coverage==0 || !date.isAfter(periodEnd))
                throw new IllegalArgumentException("Official list coverage incomplete");
            var stored=jdbc.queryForList("SELECT sha256,zip_bytes FROM report_document WHERE company_id=? AND receipt_number=?",
                company,profile.receiptNumber());
            if(stored.size()!=1 || !profile.documentSha256().equals(stored.getFirst().get("sha256")))
                throw new IllegalArgumentException("Pinned source ZIP hash missing or changed");
            byte[] zip=(byte[])stored.getFirst().get("zip_bytes");
            if(!profile.documentSha256().equals(ReportVersionCollectionService.sha256(zip)))
                throw new IllegalArgumentException("Stored source bytes changed");
            var source=ReportArchiveReader.read(profile.receiptNumber(),zip,24*1024*1024);
            var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
            var companies=doc.getElementsByTag("COMPANY-NAME");
            if(companies.size()!=1 || !corpCode.equals(companies.getFirst().attr("AREGCIK")))
                throw new IllegalArgumentException("Source company mismatch");
            Map<String,Element> groups=new HashMap<>();
            for(Row row:profile.rows()) {
                groups.computeIfAbsent(row.group(),group -> {
                    var found=doc.getElementsByTag("TABLE-GROUP").stream()
                        .filter(e -> group.equals(e.attr("ACLASS"))).toList();
                    if(found.size()!=1) throw new IllegalArgumentException("Source table missing or duplicate");
                    var table=found.getFirst();
                    String text=compact(table.text());
                    String period=year+"."+String.format(Locale.ROOT,"%02d",month)+"."+periodEnd.getDayOfMonth();
                    String unit=switch((int)row.displayUnit()) {case 1 -> "원";case 1000 -> "천원";
                        case 1000000 -> "백만원";default -> throw new IllegalArgumentException("Unsupported unit");};
                    if(!text.contains(period) || !text.contains("(단위:"+unit+")"))
                        throw new IllegalArgumentException("Source period or unit changed");
                    if(!row.division().equals("BS") && (!text.contains("3개월") || !text.contains("누적")))
                        throw new IllegalArgumentException("Income period headers missing");
                    return table;
                });
            }
            if(groups.size()!=2) throw new IllegalArgumentException("One balance and one income table required");
            var raw=jdbc.queryForList("""
                SELECT DISTINCT r.id,r.payload_hash FROM financial_fact f JOIN raw_ingestion r ON r.id=f.raw_ingestion_id
                WHERE f.company_id=? AND f.fiscal_year=? AND f.report_type=? AND f.statement_type='CFS'
                  AND f.source='OPENDART' AND f.receipt_number=?
                """,company,year,reportType,profile.receiptNumber());
            if(raw.size()!=1) throw new IllegalArgumentException("One matching financial API snapshot required");
            long rawId=((Number)raw.getFirst().get("id")).longValue();
            Map<StandardAccount,BigDecimal> balances=new EnumMap<>(StandardAccount.class);
            for(Row row:profile.rows()) {
                var matches=groups.get(row.group()).getElementsByTag("TR").stream().filter(tr -> {
                    var cells=cells(tr);
                    return cells.size()>1 && compact(cells.getFirst().text()).equals(compact(row.label()))
                        && compact(cells.get(1).text()).equals(compact(row.sourceCurrent()))
                        && (row.division().equals("BS") || cells.size()>2
                            && compact(cells.get(2).text()).equals(compact(row.sourceYtd())));
                }).toList();
                if(matches.size()!=1) throw new IllegalArgumentException("Exact source label ambiguous: "+row.account());
                var cells=cells(matches.getFirst());
                if(cells.size()!=row.sourceCells() || cells.size()!=(row.division().equals("BS")?3:5))
                    throw new IllegalArgumentException("Source column layout changed");
                BigDecimal amount=read(cells.get(1),row.sourceCurrent(),row.displayUnit(),
                    row.sourceCurrentCode(),row.sourceCurrentContext());
                BigDecimal cumulative=row.division().equals("BS")?null:
                    read(cells.get(2),row.sourceYtd(),row.displayUnit(),row.sourceYtdCode(),row.sourceYtdContext());
                if(amount.compareTo(row.amount())!=0 || (cumulative==null ? row.cumulative()!=null
                    : row.cumulative()==null || cumulative.compareTo(row.cumulative())!=0))
                    throw new IllegalArgumentException("Pinned source amount changed");
                var facts=jdbc.queryForList("""
                    SELECT amount,cumulative_amount,currency,account_code FROM financial_fact
                    WHERE company_id=? AND fiscal_year=? AND report_type=? AND statement_type='CFS'
                      AND source='OPENDART' AND receipt_number=? AND raw_ingestion_id=?
                      AND statement_division=? AND standard_account=? AND account_detail='-'
                    """,company,year,reportType,profile.receiptNumber(),rawId,row.division(),row.account());
                if(facts.size()!=1) throw new IllegalArgumentException("Matching API account missing or ambiguous");
                var fact=facts.getFirst();
                if(!"KRW".equals(fact.get("currency")) || ((BigDecimal)fact.get("amount")).compareTo(amount)!=0
                    || (cumulative==null ? fact.get("cumulative_amount")!=null
                        : fact.get("cumulative_amount")==null ||
                            ((BigDecimal)fact.get("cumulative_amount")).compareTo(cumulative)!=0))
                    throw new IllegalArgumentException("Source/API account amount differs");
                if(row.sourceCurrentCode()!=null && row.sourceCurrentCode().contains("|")) {
                    String sourceCode=row.sourceCurrentCode().substring(0,row.sourceCurrentCode().indexOf('|'));
                    String apiCode=(String)fact.get("account_code");
                    boolean sourceSpecificCode=sourceCode.startsWith("entity"+corpCode+"_udf_")
                        && "-표준계정코드 미사용-".equals(apiCode);
                    if(!sourceCode.equals(apiCode) && !sourceSpecificCode)
                        throw new IllegalArgumentException("Source composite code and API account code differ");
                }
                if(row.division().equals("BS")) balances.put(StandardAccount.valueOf(row.account()),amount);
            }
            if(balances.size()!=3 || balances.get(StandardAccount.ASSETS).compareTo(
                balances.get(StandardAccount.LIABILITIES).add(balances.get(StandardAccount.EQUITY)))!=0)
                throw new IllegalArgumentException("Balance totals do not reconcile");
            String evidence;
            try {evidence=mapper.writeValueAsString(Map.of("profile",profile,"apiPayloadSha256",
                raw.getFirst().get("payload_hash"),"scope","EXACT_SOURCE_ROWS_WITHOUT_EXTERNAL_ACCOUNT_CODES"));}
            catch(Exception ex) {throw new IllegalArgumentException("Cannot encode reviewed evidence");}
            String profileName=corpCode+"_"+year+"_"+reportType+"_CFS_EXACT_ROWS_V1";
            var versions=jdbc.queryForList("""
                INSERT INTO quarterly_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
                  main_entry,document_encoding,extraction_profile,evidence)
                VALUES (?,?,?,'CFS',?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(company_id,fiscal_year,report_type,statement_type,receipt_number)
                DO UPDATE SET receipt_number=EXCLUDED.receipt_number
                WHERE quarterly_report_version.main_entry=EXCLUDED.main_entry
                  AND quarterly_report_version.document_encoding=EXCLUDED.document_encoding
                  AND quarterly_report_version.extraction_profile=EXCLUDED.extraction_profile
                  AND quarterly_report_version.evidence=EXCLUDED.evidence RETURNING id
                """,Long.class,company,year,reportType,profile.receiptNumber(),source.mainEntry(),
                source.encoding(),profileName,evidence);
            if(versions.size()!=1) throw new IllegalArgumentException("Previously reviewed source version changed");
            long version=versions.getFirst();
            for(Row row:profile.rows()) {
                jdbc.update("""
                    INSERT INTO quarterly_version_fact(version_id,standard_account,raw_account_name,statement_division,
                      amount,cumulative_amount,currency,display_unit) VALUES (?,?,?,?,?,?,'KRW',?)
                    ON CONFLICT(version_id,standard_account) DO NOTHING
                    """,version,row.account(),row.label(),row.division(),row.amount(),row.cumulative(),row.displayUnit());
                Integer matched=jdbc.queryForObject("""
                    SELECT count(*) FROM quarterly_version_fact WHERE version_id=? AND standard_account=?
                      AND raw_account_name=? AND statement_division=? AND amount=?
                      AND cumulative_amount IS NOT DISTINCT FROM ? AND currency='KRW' AND display_unit=?
                    """,Integer.class,version,row.account(),row.label(),row.division(),row.amount(),row.cumulative(),row.displayUnit());
                if(matched==null || matched!=1) throw new IllegalArgumentException("Stored reviewed row changed");
            }
            if(!Integer.valueOf(6).equals(jdbc.queryForObject(
                "SELECT count(*) FROM quarterly_version_fact WHERE version_id=?",Integer.class,version)))
                throw new IllegalArgumentException("Incomplete reviewed row version");
            return Map.<String,Object>of("status","SIX_TOTALS_REVIEWED_NOT_MODEL_APPROVED",
                "corpCode",corpCode,"fiscalYear",year,"reportType",reportType,
                "receiptNumber",profile.receiptNumber(),"versionId",version,"accounts",6);
        });
    }

    private static String key(String corp,int year,String report) {return corp+":"+year+":"+report;}
    private static List<Element> cells(Element row) {
        return row.children().stream().filter(e -> Set.of("TE","TD","TH").contains(e.tagName().toUpperCase(Locale.ROOT))).toList();
    }
    private static String compact(String text) {return text.replaceAll("[\\s\\u00a0\\u3000]+","").trim();}
    private static BigDecimal read(Element cell,String pinnedDisplay,long unit,String sourceCode,String sourceContext) {
        if(!Set.of("TE","TD").contains(cell.tagName().toUpperCase(Locale.ROOT))
            || (sourceCode==null ? cell.hasAttr("ACODE") : !sourceCode.equals(cell.attr("ACODE")))
            || (sourceContext==null ? cell.hasAttr("ACONTEXT") : !sourceContext.equals(cell.attr("ACONTEXT")))
            || !compact(cell.text()).equals(compact(pinnedDisplay)))
            throw new IllegalArgumentException("Exact plain source cell changed");
        BigDecimal value=DartTransformer.amount(cell.text());
        if(value==null || value.scale()>0) throw new IllegalArgumentException("Invalid plain source amount");
        return value.multiply(BigDecimal.valueOf(unit));
    }
}
