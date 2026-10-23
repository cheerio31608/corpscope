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

/** Nine explicit 2025 Q3 CFS source profiles; no inference of unknown labels or units. */
@Component
public class ReviewedQ3ReportParser {
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private record Rule(StandardAccount account,String group,String label,String division,String code,String context) {}
    public record Account(StandardAccount standardAccount,String rawAccountName,String statementDivision,
                          BigDecimal amount,BigDecimal cumulativeAmount,long displayUnit) {}
    public record Parsed(String mainEntry,String encoding,String profile,List<Account> accounts,Map<String,Object> evidence) {}

    public Parsed parse(String corpCode,String receipt,byte[] zip) {
        var profile=ReviewedQ3Profiles.BY_CORP.get(corpCode);
        if(profile==null || !profile.receipt().equals(receipt))
            throw new IllegalArgumentException("Receipt is outside the reviewed Q3 profiles");
        var source=ReportArchiveReader.read(receipt,zip,
            corpCode.equals("00159193") ? 24*1024*1024:12*1024*1024);
        var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if(companies.size()!=1 || !corpCode.equals(companies.getFirst().attr("AREGCIK"))) throw invalid("Company mismatch");
        String displayName=switch((int)profile.displayUnit()) {
            case 1 -> "원";case 1000 -> "천원";case 1000000 -> "백만원";default -> throw invalid("Unsupported source unit");
        };
        String decimal=switch((int)profile.displayUnit()) {
            case 1 -> "0";case 1000 -> "-3";case 1000000 -> "-6";default -> throw invalid("Unsupported source scale");
        };
        Map<String,Element> groups=new HashMap<>();
        for(String groupCode:List.of("{XBRL}BS_C",profile.incomeGroup())) {
            var found=doc.getElementsByTag("TABLE-GROUP").stream()
                .filter(e -> groupCode.equals(e.attr("ACLASS"))).toList();
            if(found.size()!=1) throw invalid("Missing or duplicate reviewed statement group");
            var group=found.getFirst();
            String header=compact(group.text());
            if(!header.contains("2025.09.30") || !header.contains("(단위:"+displayName+")"))
                throw invalid("Reviewed source period or unit mismatch");
            if(groupCode.equals(profile.incomeGroup()) && (!header.contains("3개월") || !header.contains("누적")))
                throw invalid("Three-month and cumulative column headings missing");
            groups.put(groupCode,group);
        }
        List<Rule> rules=List.of(
            new Rule(StandardAccount.ASSETS,"{XBRL}BS_C","자산총계","BS","ifrs-full_Assets","CFY2025eTQA"),
            new Rule(StandardAccount.LIABILITIES,"{XBRL}BS_C","부채총계","BS","ifrs-full_Liabilities","CFY2025eTQA"),
            new Rule(StandardAccount.EQUITY,"{XBRL}BS_C","자본총계","BS","ifrs-full_Equity","CFY2025eTQA"),
            new Rule(StandardAccount.REVENUE,profile.incomeGroup(),profile.revenueLabel(),profile.incomeDivision(),
                "ifrs-full_Revenue","CFY2025dTQQ"),
            new Rule(StandardAccount.OPERATING_PROFIT,profile.incomeGroup(),profile.operatingLabel(),profile.incomeDivision(),
                "dart_OperatingIncomeLoss","CFY2025dTQQ"),
            new Rule(StandardAccount.NET_INCOME,profile.incomeGroup(),profile.netIncomeLabel(),profile.incomeDivision(),
                "ifrs-full_ProfitLoss","CFY2025dTQQ"));
        List<Account> accounts=new ArrayList<>();
        List<Map<String,Object>> evidenceRows=new ArrayList<>();
        for(var rule:rules) {
            var rows=groups.get(rule.group()).getElementsByTag("TR").stream().filter(row -> {
                var cells=cells(row);
                return !cells.isEmpty() && compact(cells.getFirst().text()).equals(compact(rule.label()));
            }).toList();
            if(rows.size()!=1) throw invalid("Missing or duplicate reviewed account: "+rule.account());
            var cells=cells(rows.getFirst());
            if(cells.size()!=(rule.division().equals("BS") ? 3:5)) throw invalid("Unexpected account columns");
            var amount=amount(cells.get(1),rule.code(),rule.context()+AXIS,decimal,profile.displayUnit());
            BigDecimal cumulative=null;
            if(!rule.division().equals("BS"))
                cumulative=amount(cells.get(2),rule.code(),"CFY2025dTQA"+AXIS,decimal,profile.displayUnit());
            accounts.add(new Account(rule.account(),cells.getFirst().text(),rule.division(),amount,cumulative,profile.displayUnit()));
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("account",rule.account());row.put("tableGroup",rule.group());row.put("rowLabel",cells.getFirst().text());
            row.put("code",rule.code());row.put("currentContext",rule.context()+AXIS);
            row.put("currentDisplayAmount",cells.get(1).text());
            if(cumulative!=null) {row.put("cumulativeContext","CFY2025dTQA"+AXIS);
                row.put("cumulativeDisplayAmount",cells.get(2).text());}
            evidenceRows.add(row);
        }
        Map<StandardAccount,BigDecimal> balances=new EnumMap<>(StandardAccount.class);
        accounts.stream().filter(a -> a.statementDivision().equals("BS"))
            .forEach(a -> balances.put(a.standardAccount(),a.amount()));
        if(balances.get(StandardAccount.ASSETS).compareTo(
            balances.get(StandardAccount.LIABILITIES).add(balances.get(StandardAccount.EQUITY)))!=0)
            throw invalid("Balance sheet totals do not reconcile");
        return new Parsed(source.mainEntry(),source.encoding(),corpCode+"_2025_Q3_CFS_SIX_TOTALS_V1",List.copyOf(accounts),
            Map.of("corpCode",corpCode,"receiptNumber",receipt,"fiscalYear",2025,"reportType","Q3",
                "statementType","CFS","displayUnit",profile.displayUnit(),"incomeSourceDivision",profile.incomeDivision(),
                "scope","SIX_CURRENT_CFS_TOTALS_BS_BALANCE_INCOME_THREE_MONTH_AND_YTD",
                "rows",List.copyOf(evidenceRows)));
    }

    private static List<Element> cells(Element row) {
        return row.children().stream().filter(e -> Set.of("TE","TD","TH").contains(e.tagName().toUpperCase(Locale.ROOT))).toList();
    }
    private static BigDecimal amount(Element cell,String code,String context,String decimal,long unit) {
        if(!cell.tagName().equalsIgnoreCase("TE") || !code.equals(cell.attr("ACODE"))
            || !context.equals(cell.attr("ACONTEXT")) || !decimal.equals(cell.attr("ADECIMAL"))
            || !"N".equals(cell.attr("ANEGATED"))) throw invalid("Account code, context or source scale mismatch");
        BigDecimal display=DartTransformer.amount(cell.text());
        if(display==null || display.scale()>0) throw invalid("Missing or nonintegral source amount");
        return display.multiply(BigDecimal.valueOf(unit));
    }
    private static String compact(String text) {return text.replaceAll("[\\s\\u00a0\\u3000]+","").trim();}
    private static DataQualityException invalid(String message) {
        return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message);
    }
}
