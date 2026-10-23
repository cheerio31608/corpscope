package io.corpscope.financial.vintage;

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

/** Exact receipt/hash/source-cell profiles for 2025 Q1 and half-year; no inferred fallback. */
@Service
public class Reviewed2025EarlyPeriodService {
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    public record Cell(String account,String division,String group,String code,String label,String context,
                       BigDecimal amount,BigDecimal cumulative,long displayUnit) {}
    public record Profile(String corpCode,int fiscalYear,String reportType,String receiptNumber,
                          String documentSha256,List<Cell> rows) {}
    public record Manifest(List<Profile> profiles) {}
    private final Map<String,Profile> profiles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public Reviewed2025EarlyPeriodService(JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.mapper=mapper;this.transaction=new TransactionTemplate(manager);
        try(var in=new ClassPathResource("reviewed-2025-early-periods.json").getInputStream()) {
            Manifest manifest=mapper.readValue(in,Manifest.class);
            Map<String,Profile> indexed=new HashMap<>();
            if(manifest.profiles()==null || manifest.profiles().size()!=18) throw new IllegalArgumentException();
            for(Profile profile:manifest.profiles()) {
                if(profile.fiscalYear()!=2025 || !Set.of("Q1","HALF_YEAR").contains(profile.reportType())
                    || profile.rows()==null || profile.rows().size()!=6 ||
                    !profile.documentSha256().matches("[0-9a-f]{64}") ||
                    indexed.put(key(profile.corpCode(),profile.reportType()),profile)!=null)
                    throw new IllegalArgumentException();
            }
            this.profiles=Map.copyOf(indexed);
        } catch(Exception ex) {throw new IllegalStateException("Invalid reviewed 2025 early-period manifest",ex);}
    }

    public Map<String,Object> review(String corpCode,String reportType) {
        Profile profile=profiles.get(key(corpCode,reportType));
        if(profile==null) throw new IllegalArgumentException("No exact reviewed Q1/half-year profile");
        return transaction.execute(status -> {
            var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=? FOR UPDATE",Long.class,corpCode);
            if(ids.size()!=1) throw new IllegalArgumentException("Company missing");
            long company=ids.getFirst();
            int month=reportType.equals("Q1")?3:6;
            LocalDate periodEnd=LocalDate.of(2025,month,1).withDayOfMonth(
                LocalDate.of(2025,month,1).lengthOfMonth());
            Integer coverage=jdbc.queryForObject("""
                SELECT count(*) FROM periodic_filing_coverage WHERE company_id=? AND fiscal_year=2025
                  AND report_type=? AND checked_from<=? AND checked_through>='2025-12-31'
                """,Integer.class,company,reportType,periodEnd);
            if(coverage==null || coverage==0) throw new IllegalArgumentException("Complete official period list missing");
            String title=(reportType.equals("Q1")?"분기보고서 (2025.03)":"반기보고서 (2025.06)");
            var publications=jdbc.queryForList("""
                SELECT receipt_date FROM report_publication WHERE company_id=? AND receipt_number=? AND report_title=?
                """,company,profile.receiptNumber(),title);
            if(publications.size()!=1) throw new IllegalArgumentException("Exact official publication missing");
            LocalDate receiptDate=((java.sql.Date)publications.getFirst().get("receipt_date")).toLocalDate();
            if(!receiptDate.isAfter(periodEnd) || receiptDate.isAfter(LocalDate.of(2025,12,31)))
                throw new IllegalArgumentException("Official publication outside verified scope");
            var stored=jdbc.queryForList("SELECT sha256,zip_bytes FROM report_document WHERE company_id=? AND receipt_number=?",
                company,profile.receiptNumber());
            if(stored.size()!=1 || !profile.documentSha256().equals(stored.getFirst().get("sha256")))
                throw new IllegalArgumentException("Exact source ZIP hash missing or changed");
            byte[] zip=(byte[])stored.getFirst().get("zip_bytes");
            if(!profile.documentSha256().equals(ReportVersionCollectionService.sha256(zip)))
                throw new IllegalArgumentException("Stored ZIP bytes changed");
            var source=ReportArchiveReader.read(profile.receiptNumber(),zip,24*1024*1024);
            var document=Jsoup.parse(source.text(),"",Parser.xmlParser());
            var companies=document.getElementsByTag("COMPANY-NAME");
            if(companies.size()!=1 || !corpCode.equals(companies.getFirst().attr("AREGCIK")))
                throw new IllegalArgumentException("Source company mismatch");
            Map<String,Element> groups=new HashMap<>();
            for(Cell cell:profile.rows()) {
                groups.computeIfAbsent(cell.group(),group -> {
                    var found=document.getElementsByTag("TABLE-GROUP").stream()
                        .filter(e -> group.equals(e.attr("ACLASS"))).toList();
                    if(found.size()!=1) throw new IllegalArgumentException("Source table group missing or duplicate");
                    Element selected=found.getFirst();
                    String text=compact(selected.text());
                    String unit=switch((int)cell.displayUnit()) {
                        case 1 -> "원"; case 1000 -> "천원"; case 1000000 -> "백만원";
                        default -> throw new IllegalArgumentException("Unsupported source display unit");
                    };
                    if(!text.contains("2025."+(month==3?"03.31":"06.30")) || !text.contains("(단위:"+unit+")"))
                        throw new IllegalArgumentException("Source period or display unit changed");
                    if(!cell.division().equals("BS") && (!text.contains("3개월") || !text.contains("누적")))
                        throw new IllegalArgumentException("Income period columns missing");
                    return selected;
                });
            }
            if(groups.size()!=2) throw new IllegalArgumentException("Expected one balance and one income statement");
            var raw=jdbc.queryForList("""
                SELECT DISTINCT r.id,r.payload_hash FROM financial_fact f JOIN raw_ingestion r ON r.id=f.raw_ingestion_id
                WHERE f.company_id=? AND f.fiscal_year=2025 AND f.report_type=? AND f.statement_type='CFS'
                  AND f.source='OPENDART' AND f.receipt_number=?
                """,company,reportType,profile.receiptNumber());
            if(raw.size()!=1) throw new IllegalArgumentException("One matching API snapshot required");
            long rawId=((Number)raw.getFirst().get("id")).longValue();
            Map<StandardAccount,BigDecimal> balances=new EnumMap<>(StandardAccount.class);
            for(Cell cell:profile.rows()) {
                var rows=groups.get(cell.group()).getElementsByTag("TR").stream().filter(row -> {
                    var children=cells(row);
                    return children.size()>1 && compact(children.getFirst().text()).equals(compact(cell.label()))
                        && cell.code().equals(children.get(1).attr("ACODE"))
                        && (cell.context()+AXIS).equals(children.get(1).attr("ACONTEXT"));
                }).toList();
                if(rows.size()!=1) throw new IllegalArgumentException("Exact source account row missing or duplicate: "+cell.account());
                var children=cells(rows.getFirst());
                if(children.size()!=(cell.division().equals("BS")?3:5))
                    throw new IllegalArgumentException("Source account column count changed");
                BigDecimal amount=value(children.get(1),cell.code(),cell.context()+AXIS,cell.displayUnit());
                BigDecimal cumulative=null;
                if(!cell.division().equals("BS"))
                    cumulative=value(children.get(2),cell.code(),yearToDate(cell.context())+AXIS,cell.displayUnit());
                if(amount.compareTo(cell.amount())!=0 || (cumulative==null ? cell.cumulative()!=null
                    : cell.cumulative()==null || cumulative.compareTo(cell.cumulative())!=0))
                    throw new IllegalArgumentException("Pinned source cell amount changed");
                var facts=jdbc.queryForList("""
                    SELECT amount,cumulative_amount,currency,account_code FROM financial_fact
                    WHERE company_id=? AND fiscal_year=2025 AND report_type=? AND statement_type='CFS'
                      AND source='OPENDART' AND receipt_number=? AND raw_ingestion_id=?
                      AND statement_division=? AND standard_account=? AND account_detail='-'
                    """,company,reportType,profile.receiptNumber(),rawId,cell.division(),cell.account());
                if(facts.size()!=1) throw new IllegalArgumentException("API account missing or ambiguous");
                var fact=facts.getFirst();
                if(!cell.code().equals(fact.get("account_code")) || !"KRW".equals(fact.get("currency"))
                    || ((BigDecimal)fact.get("amount")).compareTo(amount)!=0
                    || (cumulative==null ? fact.get("cumulative_amount")!=null
                        : fact.get("cumulative_amount")==null ||
                            ((BigDecimal)fact.get("cumulative_amount")).compareTo(cumulative)!=0))
                    throw new IllegalArgumentException("Official source and stored API amount differ");
                if(cell.division().equals("BS")) balances.put(StandardAccount.valueOf(cell.account()),amount);
            }
            if(balances.size()!=3 || balances.get(StandardAccount.ASSETS).compareTo(
                balances.get(StandardAccount.LIABILITIES).add(balances.get(StandardAccount.EQUITY)))!=0)
                throw new IllegalArgumentException("Balance sheet totals do not reconcile");
            String evidence;
            try {evidence=mapper.writeValueAsString(Map.of("profile",profile,"apiPayloadSha256",
                raw.getFirst().get("payload_hash"),"scope","SIX_CURRENT_CFS_TOTALS_THREE_MONTH_AND_YTD"));}
            catch(Exception ex) {throw new IllegalArgumentException("Cannot encode reviewed evidence");}
            String profileName=corpCode+"_2025_"+reportType+"_CFS_SIX_TOTALS_V1";
            var versions=jdbc.queryForList("""
                INSERT INTO quarterly_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
                    main_entry,document_encoding,extraction_profile,evidence)
                VALUES (?,2025,?,'CFS',?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(company_id,fiscal_year,report_type,statement_type,receipt_number)
                DO UPDATE SET receipt_number=EXCLUDED.receipt_number
                WHERE quarterly_report_version.main_entry=EXCLUDED.main_entry
                  AND quarterly_report_version.document_encoding=EXCLUDED.document_encoding
                  AND quarterly_report_version.extraction_profile=EXCLUDED.extraction_profile
                  AND quarterly_report_version.evidence=EXCLUDED.evidence RETURNING id
                """,Long.class,company,reportType,profile.receiptNumber(),source.mainEntry(),source.encoding(),
                profileName,evidence);
            if(versions.size()!=1) throw new IllegalArgumentException("Previously reviewed version changed");
            long version=versions.getFirst();
            for(Cell cell:profile.rows()) {
                jdbc.update("""
                    INSERT INTO quarterly_version_fact(version_id,standard_account,raw_account_name,statement_division,
                      amount,cumulative_amount,currency,display_unit) VALUES (?,?,?,?,?,?,'KRW',?)
                    ON CONFLICT(version_id,standard_account) DO NOTHING
                    """,version,cell.account(),cell.label(),cell.division(),cell.amount(),cell.cumulative(),cell.displayUnit());
                Integer matched=jdbc.queryForObject("""
                    SELECT count(*) FROM quarterly_version_fact WHERE version_id=? AND standard_account=?
                      AND raw_account_name=? AND statement_division=? AND amount=?
                      AND cumulative_amount IS NOT DISTINCT FROM ? AND currency='KRW' AND display_unit=?
                    """,Integer.class,version,cell.account(),cell.label(),cell.division(),cell.amount(),
                    cell.cumulative(),cell.displayUnit());
                if(matched==null || matched!=1) throw new IllegalArgumentException("Stored reviewed account changed");
            }
            if(!Integer.valueOf(6).equals(jdbc.queryForObject(
                "SELECT count(*) FROM quarterly_version_fact WHERE version_id=?",Integer.class,version)))
                throw new IllegalArgumentException("Incomplete reviewed version");
            return Map.<String,Object>of("status","SIX_TOTALS_REVIEWED_NOT_MODEL_APPROVED",
                "corpCode",corpCode,"reportType",reportType,"receiptNumber",profile.receiptNumber(),
                "versionId",version,"accounts",6,"documentSha256",profile.documentSha256());
        });
    }

    private static String key(String corp,String report) {return corp+":"+report;}
    private static List<Element> cells(Element row) {
        return row.children().stream().filter(e -> Set.of("TE","TD","TH").contains(e.tagName().toUpperCase(Locale.ROOT))).toList();
    }
    private static String compact(String text) {return text.replaceAll("[\\s\\u00a0\\u3000]+","").trim();}
    private static String yearToDate(String context) {
        if(!context.endsWith("Q")) throw new IllegalArgumentException("Unexpected current-quarter context");
        return context.substring(0,context.length()-1)+"A";
    }
    private static BigDecimal value(Element cell,String code,String context,long unit) {
        String decimal=switch((int)unit) {case 1 -> "0";case 1000 -> "-3";case 1000000 -> "-6";
            default -> throw new IllegalArgumentException("Unsupported unit");};
        if(!cell.tagName().equalsIgnoreCase("TE") || !code.equals(cell.attr("ACODE"))
            || !context.equals(cell.attr("ACONTEXT")) || !decimal.equals(cell.attr("ADECIMAL"))
            || !(cell.attr("ANEGATED").isBlank() || "N".equals(cell.attr("ANEGATED"))))
            throw new IllegalArgumentException("Exact source code, context or decimal mismatch");
        BigDecimal display=DartTransformer.amount(cell.text());
        if(display==null || display.scale()>0) throw new IllegalArgumentException("Invalid source display amount");
        return display.multiply(BigDecimal.valueOf(unit));
    }
}
