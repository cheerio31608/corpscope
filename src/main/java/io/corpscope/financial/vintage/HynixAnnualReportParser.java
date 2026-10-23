package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.*;
import java.math.BigDecimal;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Reviewed SK Hynix 2022–2025 CFS profiles, not a general XBRL interpreter. */
@Component
public class HynixAnnualReportParser {
    public static final Map<String,Integer> YEARS=Map.of("20230321001209",2022,"20240319000684",2023,"20250319000665",2024,"20260317000635",2025);
    private static final Map<String,StandardAccount> LEGACY_LABELS=Map.of(
        "자산총계",StandardAccount.ASSETS,"부채총계",StandardAccount.LIABILITIES,"자본총계",StandardAccount.EQUITY,
        "매출액(주4,25,32)",StandardAccount.REVENUE,"영업이익(손실)",StandardAccount.OPERATING_PROFIT,
        "당기순이익(손실)",StandardAccount.NET_INCOME);
    private static final Map<String,StandardAccount> LABELS_2022=Map.of(
        "자산총계",StandardAccount.ASSETS,"부채총계",StandardAccount.LIABILITIES,"자본총계",StandardAccount.EQUITY,
        "매출액",StandardAccount.REVENUE,"영업이익",StandardAccount.OPERATING_PROFIT,"당기순이익",StandardAccount.NET_INCOME);
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private static final Map<String,StandardAccount> CODES=Map.of(
        "ifrs-full_Assets",StandardAccount.ASSETS,"ifrs-full_Liabilities",StandardAccount.LIABILITIES,
        "ifrs-full_Equity",StandardAccount.EQUITY,"ifrs-full_Revenue",StandardAccount.REVENUE,
        "dart_OperatingIncomeLoss",StandardAccount.OPERATING_PROFIT,"ifrs-full_ProfitLoss",StandardAccount.NET_INCOME);
    public record Parsed(ReviewedReport current,Map<StandardAccount,BigDecimal> comparativeTotals) {}

    public Parsed parse(String receipt,byte[] zip) {
        Integer year=YEARS.get(receipt);
        if(year==null) throw new IllegalArgumentException("Receipt is outside reviewed Hynix profiles");
        var source=ReportArchiveReader.read(receipt,zip);
        var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if(companies.size()!=1 || !companies.getFirst().attr("AREGCIK").equals("00164779")) throw invalid("Hynix identity mismatch");
        Map<StandardAccount,ReviewedReport.Account> current=new EnumMap<>(StandardAccount.class);
        Map<StandardAccount,BigDecimal> previous=new EnumMap<>(StandardAccount.class);
        List<Map<String,Object>> evidence=new ArrayList<>();
        for(String division:List.of("BS","IS")) {
            String groupCode=year==2022 ? (division.equals("BS") ? "{XBRL}BS":"{XBRL}IS1")
                : (division.equals("BS") ? "{XBRL}BS_C":"{XBRL}IS_C1");
            String heading=division.equals("BS") ? "연결재무상태표":"연결포괄손익계산서";
            var groups=doc.getElementsByTag("TABLE-GROUP").stream().filter(g -> groupCode.equals(g.attr("ACLASS"))).toList();
            if(groups.size()!=1 || groups.getFirst().getElementsByTag("TABLE").isEmpty()) throw invalid("Missing or duplicate Hynix statement group");
            var group=groups.getFirst();
            String header=compact(group.getElementsByTag("TABLE").getFirst().text());
            String period="제"+(year-1947)+"기"+year+(division.equals("BS") ? ".12.31현재":".01.01부터"+year+".12.31까지");
            if(!header.startsWith(heading+period) || !header.contains("단위:백만원")) throw invalid("Hynix period/unit mismatch");
            if(year<=2023) {
                StringBuilder expected=new StringBuilder(heading);
                for(int y=year;y>=year-2;y--) expected.append("제").append(y-1947).append("기").append(y)
                    .append(division.equals("BS") ? ".12.31현재":".01.01부터"+y+".12.31까지");
                expected.append("(단위:백만원)");
                var tables=group.getElementsByTag("TABLE");
                if(!header.equals(expected.toString()) || tables.size()!=2 ||
                    tables.get(1).select("THEAD TH").size()!=4 ||
                    !compact(tables.get(1).select("THEAD TH").getFirst().text()).isEmpty() ||
                    !compact(tables.get(1).getElementsByTag("THEAD").text()).equals(("제"+(year-1947)+"기제"+(year-1948)+"기제"+(year-1949)+"기")))
                    throw invalid("Hynix 2023 reviewed column layout mismatch");
            }
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Element row:group.getElementsByTag("TR")) {
                var cells=row.children().stream().filter(c -> Set.of("TE","TD","TH").contains(c.tagName())).toList();
                if(cells.size()<2) continue;
                StandardAccount account=year==2022 ? LABELS_2022.get(compact(cells.getFirst().text())) : year==2023 ? LEGACY_LABELS.get(compact(cells.getFirst().text()))
                    : CODES.get(cells.get(1).attr("ACODE"));
                if(account==null) continue;
                boolean balance=Set.of(StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY).contains(account);
                if(balance!=division.equals("BS") || cells.size()!=4) throw invalid("Hynix total row layout mismatch");
                String label=switch(account) {
                    case ASSETS -> "자산총계"; case LIABILITIES -> "부채총계"; case EQUITY -> "자본총계";
                    case NET_INCOME -> year==2022 ? "당기순이익":"당기순이익(손실)";
                    case REVENUE -> year==2022 ? "매출액":year==2023 ? "매출액(주4,25,32)":"매출액(주4,24,31)";
                    case OPERATING_PROFIT -> year==2022 ? "영업이익":"영업이익(손실)";
                    default -> throw invalid("Unsupported reviewed total");
                };
                if(!compact(cells.getFirst().text()).equals(label)) throw invalid("Hynix total label mismatch");
                List<BigDecimal> amounts=new ArrayList<>();
                for(int column=1;column<=3;column++) {
                    var value=cells.get(column);
                    String context=List.of("CFY","PFY","BPFY").get(column-1)+(year-column+1)+(balance ? "eFY":"dFY")+AXIS;
                    if(year<=2023) {
                        if(!value.tagName().equals(year==2022 ? "TD":"TE") || !value.attr("ACODE").isEmpty() || !value.attr("ACONTEXT").isEmpty() ||
                            !value.attr("ADECIMAL").isEmpty() || !value.attr("ANEGATED").isEmpty())
                            throw invalid("Hynix 2023 reviewed plain cell layout mismatch");
                    } else if(!value.tagName().equals("TE") || !CODES.containsKey(value.attr("ACODE")) ||
                        !value.attr("ACODE").equals(cells.get(1).attr("ACODE")) || !value.attr("ACONTEXT").equals(context) ||
                        !value.attr("ADECIMAL").equals("-6") || (!value.attr("ANEGATED").isEmpty() && !value.attr("ANEGATED").equals("N")))
                        throw invalid("Hynix code/context/scale mismatch");
                    BigDecimal amount=DartTransformer.amount(compact(value.text()));
                    if(amount==null) throw invalid("Hynix reviewed amount missing");
                    amounts.add(amount.multiply(BigDecimal.valueOf(1_000_000)));
                }
                if(current.putIfAbsent(account,new ReviewedReport.Account(account,cells.getFirst().text(),division,amounts.getFirst()))!=null)
                    throw invalid("Duplicate Hynix total");
                previous.put(account,amounts.get(1));
                rows.add(Map.of("account",account,"code",cells.get(1).attr("ACODE"),"cells",cells.stream().map(Element::text).toList(),
                    "contexts",cells.subList(1,4).stream().map(c -> c.attr("ACONTEXT")).toList()));
            }
            evidence.add(Map.of("division",division,"header",header,"groupCode",groupCode,"currentColumn",1,"comparativeColumn",2,
                "selectedRows",rows,"tableXml",group.outerHtml()));
        }
        if(current.size()!=6 || previous.size()!=6 || current.get(StandardAccount.ASSETS).amount().compareTo(
            current.get(StandardAccount.LIABILITIES).amount().add(current.get(StandardAccount.EQUITY).amount()))!=0 ||
            previous.get(StandardAccount.ASSETS).compareTo(previous.get(StandardAccount.LIABILITIES).add(previous.get(StandardAccount.EQUITY)))!=0)
            throw invalid("Incomplete Hynix totals or balance mismatch");
        return new Parsed(new ReviewedReport(source.mainEntry(),source.encoding(),"HYNIX_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1",
            List.copyOf(current.values()),Map.of("tables",evidence,"currency","KRW","displayUnit",1_000_000,
                "basis","CONSOLIDATED_TOTAL_OPERATIONS","period",year+"-01-01/"+year+"-12-31","scope","SIX_CURRENT_TOTALS_ONLY")),Map.copyOf(previous));
    }
    private static String compact(String s) { return s.replace("&cr;","").replaceAll("[\\s\\u00a0\\u3000]+",""); }
    private static DataQualityException invalid(String message) { return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message); }
}
