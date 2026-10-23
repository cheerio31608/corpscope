package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.financial.StatementType;
import io.corpscope.ingestion.DataQualityException;
import io.corpscope.ingestion.DartTransformer;
import java.math.BigDecimal;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** Reviewed pilot profile, deliberately not a universal DART table mapper. No external resources are loaded. */
@Component
public class Hyundai2016ReportParser {
    public static final String PROFILE = "HYUNDAI_2016_ANNUAL_CFS_SIX_TOTALS_V1";
    public static final String OFS_PROFILE = "HYUNDAI_2016_ANNUAL_OFS_SIX_TOTALS_V1";
    private static final Map<String,StandardAccount> LABELS = Map.of(
            "매출액",StandardAccount.REVENUE,"영업이익",StandardAccount.OPERATING_PROFIT,
            "연결당기순이익",StandardAccount.NET_INCOME,"자산총계",StandardAccount.ASSETS,
            "부채총계",StandardAccount.LIABILITIES,"자본총계",StandardAccount.EQUITY);

    public ReviewedReport parse(String receipt, byte[] zip) {
        return parse(receipt,zip,StatementType.CFS);
    }

    public ReviewedReport parse(String receipt, byte[] zip,StatementType statement) {
        if (statement==null) throw new IllegalArgumentException("Statement scope is required");
        boolean consolidated=statement==StatementType.CFS;
        if (!Set.of("20170331004820","20220217000946").contains(receipt)) {
            throw new IllegalArgumentException("Receipt is outside the reviewed Hyundai 2016 pilot");
        }
        var source=ReportArchiveReader.read(receipt,zip);
        String entryName=source.mainEntry(), encoding=source.encoding(), text=source.text();
        var doc=Jsoup.parse(text,"",Parser.xmlParser());
        var companies=doc.getElementsByTag("COMPANY-NAME");
        if (companies.size()!=1 || !"00164742".equals(companies.getFirst().attr("AREGCIK"))) {
            throw invalid("Report company identity mismatch");
        }
        Map<StandardAccount,ReviewedReport.Account> accounts=new EnumMap<>(StandardAccount.class);
        List<Map<String,Object>> evidence=new ArrayList<>();
        for (String division: List.of("BS","IS")) {
            String className="BS".equals(division)
                    ? (consolidated ? "{XBRL}BS":"{XBRL}BS_S")
                    : (consolidated ? "{XBRL}IS2":"{XBRL}IS_S2");
            String heading=(consolidated ? "연결":"")+("BS".equals(division) ? "재무상태표":"손익계산서");
            var groups=doc.getElementsByTag("TABLE-GROUP").stream()
                    .filter(g -> className.equals(g.attr("ACLASS")))
                    .filter(g -> !g.getElementsByTag("TABLE").isEmpty())
                    .filter(g -> compact(g.getElementsByTag("TABLE").getFirst().text()).startsWith(heading)).toList();
            if (groups.size()!=1) throw invalid("Missing or ambiguous "+statement+" statement group");
            Element group=groups.getFirst();
            String header=compact(group.getElementsByTag("TABLE").getFirst().text());
            if (!header.contains("단위:백만원") || !("BS".equals(division)
                    ? header.startsWith(heading+"제49기2016.12.31현재")
                    : header.startsWith(heading+"제49기2016.01.01부터2016.12.31까지"))) {
                throw invalid("Unexpected current period or display unit");
            }
            List<List<String>> selectedRows=new ArrayList<>();
            for (Element row: group.getElementsByTag("TR")) {
                var cells=row.children().stream().filter(c -> Set.of("TD","TH").contains(c.tagName())).toList();
                if (cells.isEmpty()) continue;
                String label=compact(cells.getFirst().text());
                StandardAccount account=(!consolidated && "당기순이익".equals(label)) ? StandardAccount.NET_INCOME:LABELS.get(label);
                if (!consolidated && "연결당기순이익".equals(label)) account=null;
                if (account==null) continue;
                boolean balance=Set.of(StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY).contains(account);
                if (balance != "BS".equals(division) || cells.size()!=4) throw invalid("Unexpected total row layout");
                String value=compact(cells.get(1).text());
                BigDecimal amount=DartTransformer.amount(value);
                if (amount==null) throw invalid("Missing reviewed current total");
                var fact=new ReviewedReport.Account(account,cells.getFirst().text(),division,amount.multiply(BigDecimal.valueOf(1_000_000)));
                if (accounts.putIfAbsent(account,fact)!=null) throw invalid("Duplicate reviewed total");
                selectedRows.add(cells.stream().map(Element::text).toList());
            }
            evidence.add(Map.of("division",division,"header",header,"currentColumn",1,
                    "displayUnit",1_000_000,"selectedRows",selectedRows,"tableXml",group.outerHtml()));
        }
        if (accounts.size()!=6 || accounts.get(StandardAccount.ASSETS).amount().compareTo(
                accounts.get(StandardAccount.LIABILITIES).amount().add(accounts.get(StandardAccount.EQUITY).amount()))!=0) {
            throw invalid("Incomplete reviewed totals or balance equation mismatch");
        }
        return new ReviewedReport(entryName,encoding,consolidated ? PROFILE:OFS_PROFILE,List.copyOf(accounts.values()),
                Map.of("tables",evidence,"currency","KRW","basis",consolidated ? "CONSOLIDATED_TOTAL_OPERATIONS":"SEPARATE_TOTAL_OPERATIONS",
                        "period","2016-01-01/2016-12-31","scope","SIX_CURRENT_TOTALS_ONLY"));
    }

    private static DataQualityException invalid(String message) {
        return new DataQualityException(DataQualityException.Code.RESPONSE_STRUCTURE,message);
    }

    private static String compact(String s) { return s.replace("&cr;","").replaceAll("[\\s\\u00a0\\u3000]+",""); }
}
