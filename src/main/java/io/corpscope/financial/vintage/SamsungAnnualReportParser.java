package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.*;
import java.math.BigDecimal;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Reviewed Samsung 2020–2025 CFS profiles, not a general XBRL interpreter. */
@Component
public class SamsungAnnualReportParser {
    public static final Map<String,Integer> YEARS=Map.of("20210309000744",2020,"20220308000798",2021,"20230307000542",2022,"20240312000736",2023,"20250311001085",2024,"20260310002820",2025);
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private static final Map<String,StandardAccount> CODES=Map.of(
        "ifrs-full_Assets",StandardAccount.ASSETS,"ifrs-full_Liabilities",StandardAccount.LIABILITIES,
        "ifrs-full_Equity",StandardAccount.EQUITY,"ifrs-full_Revenue",StandardAccount.REVENUE,
        "dart_OperatingIncomeLoss",StandardAccount.OPERATING_PROFIT,"ifrs-full_ProfitLoss",StandardAccount.NET_INCOME);
    public record Parsed(ReviewedReport current,Map<StandardAccount,BigDecimal> comparativeTotals) {}

    public Parsed parse(String receipt,byte[] zip) {
        Integer year=YEARS.get(receipt);
        if(year==null) throw new IllegalArgumentException("Receipt is outside reviewed Samsung profiles");
        var source=ReportArchiveReader.read(receipt,zip);
        var doc=Jsoup.parse(source.text(),"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if(companies.size()!=1 || !companies.getFirst().attr("AREGCIK").equals("00126380")) throw invalid("Samsung identity mismatch");
        Map<StandardAccount,ReviewedReport.Account> current=new EnumMap<>(StandardAccount.class);
        Map<StandardAccount,BigDecimal> previous=new EnumMap<>(StandardAccount.class);
        List<Map<String,Object>> evidence=new ArrayList<>();
        for(String division:List.of("BS","IS")) {
            String groupCode=year<=2022 ? (division.equals("BS") ? "{XBRL}BS":"{XBRL}IS2") : (division.equals("BS") ? "{XBRL}BS_C":"{XBRL}IS_C2");
            String heading=division.equals("BS") ? "연결재무상태표":"연결손익계산서";
            var groups=doc.getElementsByTag("TABLE-GROUP").stream().filter(g -> groupCode.equals(g.attr("ACLASS"))).toList();
            if(groups.size()!=1 || groups.getFirst().getElementsByTag("TABLE").isEmpty()) throw invalid("Missing or duplicate Samsung statement group");
            var group=groups.getFirst();
            String header=compact(group.getElementsByTag("TABLE").getFirst().text());
            String period="제"+(year-1968)+"기"+year+(division.equals("BS") ? ".12.31현재":".01.01부터"+year+".12.31까지");
            if(!header.startsWith(heading+period) || !header.contains("단위:백만원")) throw invalid("Samsung period/unit mismatch");
            if(year<=2023) {
                // Untagged columns require the explicit three-year header in reviewed order.
                int position=-1;
                for(int offset=0;offset<3;offset++) {
                    int columnYear=year-offset;
                    String columnPeriod="제"+(columnYear-1968)+"기"+columnYear+
                        (division.equals("BS") ? ".12.31현재":".01.01부터"+columnYear+".12.31까지");
                    int found=header.indexOf(columnPeriod);
                    if(found<=position) throw invalid("Samsung legacy column periods mismatch");
                    position=found;
                }
            }
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Element row:group.getElementsByTag("TR")) {
                var cells=row.children().stream().filter(c -> Set.of("TE","TD","TH").contains(c.tagName())).toList();
                if(cells.size()<2) continue;
                // Reviewed 2020/2021/2022 archives use TD, and 2023 uses plain TE cells without XBRL attributes.
                StandardAccount account=year<=2023 ? legacyAccount(compact(cells.getFirst().text())) : CODES.get(cells.get(1).attr("ACODE"));
                if(account==null) continue;
                boolean balance=Set.of(StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY).contains(account);
                if(balance!=division.equals("BS") || cells.size()!=4) throw invalid("Samsung total row layout mismatch");
                String label=switch(account) {
                    case ASSETS -> "자산총계"; case LIABILITIES -> "부채총계"; case EQUITY -> "자본총계";
                    case NET_INCOME -> year<=2023 ? "당기순이익(손실)":"당기순이익";
                    case REVENUE -> year<=2022 ? "수익(매출액)":year==2023 ? "영업수익(주29)":"매출액(주"+(year==2024 ? 29:30)+")";
                    case OPERATING_PROFIT -> year<=2022 ? "영업이익":"영업이익(주"+(year<=2024 ? 29:30)+")";
                    default -> throw invalid("Unsupported reviewed total");
                };
                if(!compact(cells.getFirst().text()).equals(label)) throw invalid("Samsung total label mismatch");
                List<BigDecimal> amounts=new ArrayList<>();
                for(int column=1;column<=3;column++) {
                    var value=cells.get(column);
                    String context=List.of("CFY","PFY","BPFY").get(column-1)+(year-column+1)+(balance ? "eFY":"dFY")+AXIS;
                    if(year<=2023) {
                        if(!value.tagName().equals(year<=2022 ? "TD":"TE") || !value.attr("ACODE").isEmpty() || !value.attr("ACONTEXT").isEmpty() ||
                            !value.attr("ADECIMAL").isEmpty() || !value.attr("ANEGATED").isEmpty())
                            throw invalid("Unexpected attributes in reviewed Samsung legacy row");
                    } else if(!value.tagName().equals("TE") || !CODES.containsKey(value.attr("ACODE")) ||
                        !value.attr("ACODE").equals(cells.get(1).attr("ACODE")) || !value.attr("ACONTEXT").equals(context) ||
                        !value.attr("ADECIMAL").equals("-6") || (!value.attr("ANEGATED").isEmpty() && !value.attr("ANEGATED").equals("N")))
                        throw invalid("Samsung code/context/scale mismatch");
                    BigDecimal amount=DartTransformer.amount(compact(value.text()));
                    if(amount==null) throw invalid("Samsung reviewed amount missing");
                    amounts.add(amount.multiply(BigDecimal.valueOf(1_000_000)));
                }
                if(current.putIfAbsent(account,new ReviewedReport.Account(account,cells.getFirst().text(),division,amounts.getFirst()))!=null)
                    throw invalid("Duplicate Samsung total");
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
            throw invalid("Incomplete Samsung totals or balance mismatch");
        return new Parsed(new ReviewedReport(source.mainEntry(),source.encoding(),"SAMSUNG_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1",
            List.copyOf(current.values()),Map.of("tables",evidence,"currency","KRW","displayUnit",1_000_000,
                "basis","CONSOLIDATED_TOTAL_OPERATIONS","period",year+"-01-01/"+year+"-12-31","scope","SIX_CURRENT_TOTALS_ONLY")),Map.copyOf(previous));
    }
    private static StandardAccount legacyAccount(String label) {
        return switch(label) {
            case "자산총계" -> StandardAccount.ASSETS;
            case "부채총계" -> StandardAccount.LIABILITIES;
            case "자본총계" -> StandardAccount.EQUITY;
            case "수익(매출액)","영업수익(주29)" -> StandardAccount.REVENUE;
            case "영업이익","영업이익(주29)" -> StandardAccount.OPERATING_PROFIT;
            case "당기순이익(손실)" -> StandardAccount.NET_INCOME;
            default -> null;
        };
    }
    private static String compact(String s) { return s.replace("&cr;","").replaceAll("[\\s\\u00a0\\u3000]+",""); }
    private static DataQualityException invalid(String message) { return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message); }
}
