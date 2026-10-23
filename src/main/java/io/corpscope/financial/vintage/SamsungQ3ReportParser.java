package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DartTransformer;
import io.corpscope.ingestion.DataQualityException;
import java.math.BigDecimal;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Exactly one source-reviewed report layout, not a generic quarterly XBRL interpreter. */
@Component
public class SamsungQ3ReportParser {
    public static final String RECEIPT = "20251114002447";
    public static final String PROFILE = "SAMSUNG_2025_Q3_CFS_SIX_TOTALS_V1";
    private static final String AXIS = "_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private record Rule(StandardAccount account,String group,String label,String division,String code,String context) {}
    private static final List<Rule> RULES=List.of(
        new Rule(StandardAccount.ASSETS,"{XBRL}BS_C","자산총계","BS","ifrs-full_Assets","CFY2025eTQA"),
        new Rule(StandardAccount.LIABILITIES,"{XBRL}BS_C","부채총계","BS","ifrs-full_Liabilities","CFY2025eTQA"),
        new Rule(StandardAccount.EQUITY,"{XBRL}BS_C","자본총계","BS","ifrs-full_Equity","CFY2025eTQA"),
        new Rule(StandardAccount.REVENUE,"{XBRL}IS_C2","매출액 (주27)","IS","ifrs-full_Revenue","CFY2025dTQQ"),
        new Rule(StandardAccount.OPERATING_PROFIT,"{XBRL}IS_C2","영업이익 (주27)","IS","dart_OperatingIncomeLoss","CFY2025dTQQ"),
        new Rule(StandardAccount.NET_INCOME,"{XBRL}IS_C2","분기순이익","IS","ifrs-full_ProfitLoss","CFY2025dTQQ")
    );
    public record Account(StandardAccount standardAccount,String rawAccountName,String statementDivision,
                          BigDecimal amount,BigDecimal cumulativeAmount) {}
    public record Parsed(String mainEntry,String encoding,String profile,List<Account> accounts,Map<String,Object> evidence) {}

    public Parsed parse(String receipt,byte[] zip) {
        if(!RECEIPT.equals(receipt)) throw new IllegalArgumentException("Receipt is outside the reviewed Samsung Q3 profile");
        var source=ReportArchiveReader.read(receipt,zip);
        var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if(companies.size()!=1 || !"00126380".equals(companies.getFirst().attr("AREGCIK"))) throw invalid("Company mismatch");
        Map<String,Element> groups=new HashMap<>();
        for(String groupCode:List.of("{XBRL}BS_C","{XBRL}IS_C2")) {
            var found=doc.getElementsByTag("TABLE-GROUP").stream().filter(e -> groupCode.equals(e.attr("ACLASS"))).toList();
            if(found.size()!=1) throw invalid("Missing or duplicate statement group");
            Element group=found.getFirst();
            String header=compact(group.text());
            if(!header.contains("(단위:백만원)") || !header.contains("2025.09.30")) throw invalid("Period or display unit mismatch");
            if(groupCode.endsWith("IS_C2") && (!header.contains("3개월") || !header.contains("누적")))
                throw invalid("Three-month and cumulative headings missing");
            groups.put(groupCode,group);
        }
        List<Account> accounts=new ArrayList<>();
        List<Map<String,Object>> rowEvidence=new ArrayList<>();
        for(Rule rule:RULES) {
            var rows=groups.get(rule.group()).getElementsByTag("TR").stream().filter(row -> {
                var cells=cells(row);
                return !cells.isEmpty() && compact(cells.getFirst().text()).equals(compact(rule.label()));
            }).toList();
            if(rows.size()!=1) throw invalid("Missing or duplicate account row: " + rule.account());
            var cells=cells(rows.getFirst());
            if(cells.size()!=(rule.division().equals("BS") ? 3:5)) throw invalid("Unexpected account columns");
            var current=amount(cells.get(1),rule.code(),rule.context()+AXIS);
            BigDecimal cumulative=null;
            if(rule.division().equals("IS")) cumulative=amount(cells.get(2),rule.code(),"CFY2025dTQA"+AXIS);
            accounts.add(new Account(rule.account(),cells.getFirst().text(),rule.division(),current,cumulative));
            Map<String,Object> evidence=new LinkedHashMap<>();
            evidence.put("standardAccount",rule.account());
            evidence.put("tableGroup",rule.group());
            evidence.put("rowLabel",cells.getFirst().text());
            evidence.put("accountCode",rule.code());
            evidence.put("currentContext",rule.context()+AXIS);
            evidence.put("currentDisplayAmount",cells.get(1).text());
            if(cumulative!=null) {
                evidence.put("cumulativeContext","CFY2025dTQA"+AXIS);
                evidence.put("cumulativeDisplayAmount",cells.get(2).text());
            }
            rowEvidence.add(evidence);
        }
        Map<StandardAccount,BigDecimal> balances=new EnumMap<>(StandardAccount.class);
        accounts.stream().filter(a -> a.statementDivision().equals("BS"))
            .forEach(a -> balances.put(a.standardAccount(),a.amount()));
        if(balances.get(StandardAccount.ASSETS).compareTo(
                balances.get(StandardAccount.LIABILITIES).add(balances.get(StandardAccount.EQUITY)))!=0)
            throw invalid("Balance sheet does not reconcile");
        return new Parsed(source.mainEntry(),source.encoding(),PROFILE,List.copyOf(accounts),
            Map.of("receiptNumber",receipt,"fiscalYear",2025,"reportType","Q3","statementType","CFS",
                "displayUnit",1_000_000,"scope","SIX_CURRENT_CFS_TOTALS_BS_POINT_IN_TIME_IS_THREE_MONTH_AND_YTD",
                "rows",List.copyOf(rowEvidence)));
    }

    private static List<Element> cells(Element row) {
        return row.children().stream().filter(e -> Set.of("TE","TD","TH").contains(e.tagName().toUpperCase(Locale.ROOT))).toList();
    }
    private static BigDecimal amount(Element cell,String code,String context) {
        if(!cell.tagName().equalsIgnoreCase("TE") || !code.equals(cell.attr("ACODE"))
                || !context.equals(cell.attr("ACONTEXT")) || !"-6".equals(cell.attr("ADECIMAL"))
                || !"N".equals(cell.attr("ANEGATED"))) throw invalid("Source account code, context or scale mismatch");
        BigDecimal display=DartTransformer.amount(cell.text());
        if(display==null || display.scale()>0) throw invalid("Missing or nonintegral million-KRW amount");
        return display.multiply(BigDecimal.valueOf(1_000_000));
    }
    private static String compact(String text) {return text.replaceAll("[\\s\\u00a0\\u3000]+","").trim();}
    private static DataQualityException invalid(String message) {
        return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message);
    }
}
