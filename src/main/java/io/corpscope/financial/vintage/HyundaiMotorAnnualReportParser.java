package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.*;
import java.math.BigDecimal;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Explicit Hyundai Motor 2021–2025 annual CFS six-total profiles, not a general XBRL parser. */
@Component
public class HyundaiMotorAnnualReportParser {
    public static final Map<String,Integer> YEARS=Map.of(
        "20220316001052",2021,"20230315001030",2022,
        "20240313001451",2023,"20240314001466",2021,
        "20240314001531",2022,"20250312001148",2024,"20260318001394",2025);
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private static final Map<StandardAccount,String> CODES=Map.of(
        StandardAccount.ASSETS,"ifrs-full_Assets",StandardAccount.LIABILITIES,"ifrs-full_Liabilities",
        StandardAccount.EQUITY,"ifrs-full_Equity",StandardAccount.REVENUE,"ifrs-full_Revenue",
        StandardAccount.OPERATING_PROFIT,"dart_OperatingIncomeLoss",StandardAccount.NET_INCOME,"ifrs-full_ProfitLoss");
    public record Parsed(ReviewedReport current,Map<StandardAccount,BigDecimal> comparativeTotals) {}

    public Parsed parse(String receipt,byte[] zip) {
        Integer year=YEARS.get(receipt);
        if(year==null) throw invalid("Receipt outside Hyundai Motor profiles");
        var source=ReportArchiveReader.read(receipt,zip);
        var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if(companies.size()!=1 || !"00164742".equals(companies.getFirst().attr("AREGCIK")))
            throw invalid("Hyundai Motor identity mismatch");
        Map<StandardAccount,ReviewedReport.Account> current=new EnumMap<>(StandardAccount.class);
        Map<StandardAccount,BigDecimal> previous=new EnumMap<>(StandardAccount.class);
        List<Map<String,Object>> evidence=new ArrayList<>();
        for(String division:List.of("BS","IS")) {
            boolean balance=division.equals("BS");
            String groupCode=year<=2022 ? (balance ? "{XBRL}BS":"{XBRL}IS2")
                : (balance ? "{XBRL}BS_C":"{XBRL}IS_C2");
            var groups=doc.getElementsByTag("TABLE-GROUP").stream()
                .filter(g -> groupCode.equals(g.attr("ACLASS"))).toList();
            if(groups.size()!=1 || groups.getFirst().getElementsByTag("TABLE").isEmpty())
                throw invalid("Missing or duplicate Hyundai Motor statement group");
            var group=groups.getFirst();
            String header=compact(group.getElementsByTag("TABLE").getFirst().text());
            String heading=balance ? "연결재무상태표":"연결손익계산서";
            String period=balance ? year+".12.31현재" : year+".01.01부터"+year+".12.31까지";
            if(!header.contains(heading) || !header.contains(period) ||
                !header.contains((year-1)+".12.31") || !header.contains("(단위:백만원)"))
                throw invalid("Hyundai Motor period or display unit mismatch");
            List<Map<String,Object>> selected=new ArrayList<>();
            for(Element row:group.getElementsByTag("TR")) {
                var cells=row.children().stream().filter(c -> Set.of("TE","TD","TH").contains(c.tagName())).toList();
                if(cells.size()<2) continue;
                String rawName=compact(cells.getFirst().text());
                StandardAccount account=account(rawName,year,balance);
                if(account==null) continue;
                int columns=3;
                if(cells.size()!=columns+1) throw invalid("Hyundai Motor total column count changed");
                List<BigDecimal> amounts=new ArrayList<>();
                for(int i=1;i<=columns;i++) {
                    var cell=cells.get(i);
                    if(year<=2023) {
                        String tag=year<=2022 ? "TD":"TE";
                        if(!cell.tagName().equals(tag) || !cell.attr("ACODE").isEmpty() ||
                            !cell.attr("ACONTEXT").isEmpty()) throw invalid("Plain total cell changed");
                    } else {
                        String context=List.of("CFY","PFY","BPFY").get(i-1)+(year-i+1)+
                            (balance ? "eFY":"dFY")+AXIS;
                        if(!cell.tagName().equals("TE") || !cell.attr("ACODE").equals(CODES.get(account)) ||
                            !cell.attr("ACONTEXT").equals(context) || !cell.attr("ADECIMAL").equals("-6") ||
                            (!cell.attr("ANEGATED").isEmpty() && !cell.attr("ANEGATED").equals("N")))
                            throw invalid("Coded total context or scale changed");
                    }
                    BigDecimal amount=DartTransformer.amount(compact(cell.text()));
                    if(amount==null) throw invalid("Hyundai Motor amount missing");
                    amounts.add(amount.multiply(BigDecimal.valueOf(1_000_000)));
                }
                if(current.putIfAbsent(account,new ReviewedReport.Account(account,cells.getFirst().text(),division,amounts.getFirst()))!=null)
                    throw invalid("Duplicate Hyundai Motor total");
                previous.put(account,amounts.get(1));
                selected.add(Map.of("account",account,"rawName",cells.getFirst().text(),"code",CODES.get(account),
                    "cells",cells.stream().map(Element::text).toList(),"contexts",cells.subList(1,cells.size()).stream().map(c -> c.attr("ACONTEXT")).toList()));
            }
            evidence.add(Map.of("division",division,"groupCode",groupCode,"header",header,"currentColumn",1,
                "comparativeColumn",2,"selectedRows",selected,"tableXml",group.outerHtml()));
        }
        if(current.size()!=6 || previous.size()!=6 || !balanced(current,previous))
            throw invalid("Incomplete Hyundai Motor totals or balance mismatch");
        return new Parsed(new ReviewedReport(source.mainEntry(),source.encoding(),
            "HYUNDAI_MOTOR_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1",List.copyOf(current.values()),
            Map.of("tables",evidence,"currency","KRW","displayUnit",1_000_000,
                "basis","CONSOLIDATED_TOTAL_OPERATIONS","period",year+"-01-01/"+year+"-12-31",
                "scope","SIX_CURRENT_TOTALS_ONLY")),Map.copyOf(previous));
    }
    private static StandardAccount account(String name,int year,boolean balance) {
        if(balance) return switch(name) {
            case "자산총계" -> StandardAccount.ASSETS;
            case "부채총계" -> StandardAccount.LIABILITIES;
            case "자본총계" -> StandardAccount.EQUITY;
            default -> null;
        };
        return switch(name) {
            case "매출액" -> StandardAccount.REVENUE;
            case "영업이익" -> StandardAccount.OPERATING_PROFIT;
            case "연결당기순이익" -> StandardAccount.NET_INCOME;
            default -> null;
        };
    }
    private static boolean balanced(Map<StandardAccount,ReviewedReport.Account> current,
            Map<StandardAccount,BigDecimal> previous) {
        return current.get(StandardAccount.ASSETS).amount().compareTo(current.get(StandardAccount.LIABILITIES).amount()
            .add(current.get(StandardAccount.EQUITY).amount()))==0 &&
            previous.get(StandardAccount.ASSETS).compareTo(previous.get(StandardAccount.LIABILITIES)
                .add(previous.get(StandardAccount.EQUITY)))==0;
    }
    private static String compact(String text) { return text.replace("&cr;","").replaceAll("[\\s\\u00a0\\u3000]+",""); }
    private static DataQualityException invalid(String message) {
        return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message);
    }
}
