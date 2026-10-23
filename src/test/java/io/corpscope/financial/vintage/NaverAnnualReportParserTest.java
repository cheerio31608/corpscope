package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DataQualityException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class NaverAnnualReportParserTest {
    @ParameterizedTest @ValueSource(strings={"UTF-8","MS949"})
    void reads2021WholeGroupProfitWithoutReplacingItWithContinuingOrOwnerProfit(String encoding) throws Exception {
        String receipt="20220304000687";
        String xml=fixture(2021).replace("UTF-8",encoding);
        var result=new NaverAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,xml,java.nio.charset.Charset.forName(encoding)));
        assertThat(result.current().encoding()).isEqualTo(encoding);
        assertThat(result.current().displayUnit()).isEqualTo(1);
        assertThat(result.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("16477625559645"));
        assertThat(result.comparativeTotals().get(StandardAccount.REVENUE)).isEqualByComparingTo("5304145900386");
        assertThat(result.current().evidence().toString()).doesNotContain("ACODE=","ACONTEXT=","ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","year","column","group","revenue-note","profit-meaning","missing","balance","attribute","cell"})
    void rejectsChanged2021PlainScopeOrAmounts(String kind) throws Exception {
        String original=fixture(2021);
        String changed=switch(kind) {
            case "company" -> original.replace("00266961","00126380");
            case "unit" -> original.replace("단위 : 원","단위 : 백만원");
            case "year" -> original.replace("2021.12.31","2020.12.31");
            case "column" -> original.replace("제 22 기","제 21 기");
            case "group" -> original.replace("{XBRL}BS","{XBRL}BS_S");
            case "revenue-note" -> original.replace("영업수익 (주34)","영업수익 (주35)");
            case "profit-meaning" -> original.replace("당기순이익","계속영업순이익");
            case "missing" -> original.replace("6,817,599,707,250","");
            case "balance" -> original.replace("33,691,006,757,040","33,691,006,757,041");
            case "attribute" -> original.replace("<TD ALIGN=\"RIGHT\"","<TD ACODE=\"ifrs-full_Revenue\" ALIGN=\"RIGHT\"");
            default -> original.replace("<TD ","<TE ").replace("</TD>","</TE>");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20220304000687",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new NaverAnnualReportParser().parse("20220304000687",zip)).isInstanceOf(DataQualityException.class);
    }
    @Test void reads2022PlainTdCellsWithExplicitOldGroupAndRevenueNote() throws Exception {
        String receipt="20230314001049";
        var result=new NaverAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture(2022),StandardCharsets.UTF_8));
        assertThat(result.current().displayUnit()).isEqualTo(1);
        assertThat(result.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("673180300087"));
        assertThat(result.comparativeTotals().get(StandardAccount.REVENUE)).isEqualByComparingTo("6817599707250");
        assertThat(result.current().evidence().toString()).doesNotContain("ACODE=","ACONTEXT=","ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","year","column","group","revenue-note","profit-meaning","missing","balance","attribute","cell"})
    void rejectsChanged2022PlainScopeOrAmounts(String kind) throws Exception {
        String original=fixture(2022);
        String changed=switch(kind) {
            case "company" -> original.replace("00266961","00126380");
            case "unit" -> original.replace("단위 : 원","단위 : 백만원");
            case "year" -> original.replace("2022.12.31","2021.12.31");
            case "column" -> original.replace("제 23 기","제 22 기");
            case "group" -> original.replace("{XBRL}BS","{XBRL}BS_S");
            case "revenue-note" -> original.replace("영업수익 (주34)","영업수익 (주35)");
            case "profit-meaning" -> original.replace("당기순이익","계속영업이익");
            case "missing" -> original.replace("8,220,078,708,810","");
            case "balance" -> original.replace("33,899,042,889,136","33,899,042,889,137");
            case "attribute" -> original.replace("<TD ALIGN=\"RIGHT\"","<TD ACODE=\"ifrs-full_Revenue\" ALIGN=\"RIGHT\"");
            default -> original.replace("<TD ","<TE ").replace("</TD>","</TE>");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20230314001049",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new NaverAnnualReportParser().parse("20230314001049",zip)).isInstanceOf(DataQualityException.class);
    }
    @Test void reads2023PlainCellsWithoutInventingExternalCodes() throws Exception {
        String receipt="20240318000844";
        var result=new NaverAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture(2023),StandardCharsets.UTF_8));
        assertThat(result.current().displayUnit()).isEqualTo(1);
        assertThat(result.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("985017762493"));
        assertThat(result.comparativeTotals().get(StandardAccount.REVENUE)).isEqualByComparingTo("8220078708810");
        assertThat(result.current().evidence().toString()).doesNotContain("ACODE=","ACONTEXT=","ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","year","column","profit-meaning","missing","balance","attribute"})
    void rejectsChanged2023PlainScopeOrAmounts(String kind) throws Exception {
        String original=fixture(2023);
        String changed=switch(kind) {
            case "company" -> original.replace("00266961","00126380");
            case "unit" -> original.replace("단위 : 원","단위 : 백만원");
            case "year" -> original.replace("2023.12.31","2022.12.31");
            case "column" -> original.replace("제 24 기","제 23 기");
            case "profit-meaning" -> original.replace("당기순이익","지배기업 소유주 순이익");
            case "missing" -> original.replace("9,670,643,576,585","");
            case "balance" -> original.replace("35,737,826,964,395","35,737,826,964,396");
            default -> original.replace("<TE ALIGN=\"RIGHT\"","<TE ACODE=\"ifrs-full_Revenue\" ALIGN=\"RIGHT\"");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20240318000844",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new NaverAnnualReportParser().parse("20240318000844",zip)).isInstanceOf(DataQualityException.class);
    }
    static String fixture(int year) throws Exception {
        try(var in=NaverAnnualReportParserTest.class.getResourceAsStream("/fixtures/naver-"+year+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest @ValueSource(ints={2024,2025})
    void preservesExactWonAmountsAndOriginalUnit(int year) throws Exception {
        String receipt=year==2024 ? "20250318000645":"20260313001021";
        var parsed=new NaverAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture(year),StandardCharsets.UTF_8));
        assertThat(parsed.current().displayUnit()).isEqualTo(1);
        assertThat(parsed.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.REVENUE)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo(year==2024 ? "10737719264647":"12035007218975"));
        assertThat(parsed.comparativeTotals().get(StandardAccount.REVENUE))
            .isEqualByComparingTo(year==2024 ? "9670643576585":"10737719264647");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","currency","context","separate","group","owner-profit","label","balance","previous-balance","scale","negated","missing","year"})
    void rejectsDifferentMeaningPeriodScaleOrMissingData(String kind) throws Exception {
        String original=fixture(2025);
        String changed=switch(kind) {
            case "company" -> original.replace("00266961","00126380");
            case "unit" -> original.replace("단위 : 원","단위 : 백만원");
            case "currency" -> original.replace("Unit : KRW","Unit : USD");
            case "context" -> original.replace("CFY2025","PFY2024");
            case "separate" -> original.replace("_ConsolidatedMember","_SeparateMember");
            case "group" -> original.replace("{XBRL}IS_C1","{XBRL}IS_C2");
            case "owner-profit" -> original.replace("ifrs-full_ProfitLoss\"","ifrs-full_ProfitLossAttributableToOwnersOfParent\"");
            case "label" -> original.replace("영업수익 (주35)","광고수익 (주35)");
            case "balance" -> original.replace("41,084,496,327,318","41,084,496,327,319");
            case "previous-balance" -> original.replace("38,167,876,036,020","38,167,876,036,021");
            case "scale" -> original.replace("ADECIMAL=\"0\"","ADECIMAL=\"-6\"");
            case "negated" -> original.replace("ANEGATED=\"N\"","ANEGATED=\"Y\"");
            case "missing" -> original.replace("12,035,007,218,975","");
            default -> original.replace("2025.12.31","2024.12.31");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20260313001021",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new NaverAnnualReportParser().parse("20260313001021",zip)).isInstanceOf(DataQualityException.class);
    }
    @Test void rejectsUnreviewedReceipt() {
        assertThatThrownBy(() -> new NaverAnnualReportParser().parse("20240318000000",new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresExplicitSupportedUnitInsteadOfAssumingMillions() {
        for(var evidence:java.util.List.<Map<String,Object>>of(Map.of(),Map.of("displayUnit",10000),Map.of("displayUnit",1.5),Map.of("displayUnit","1"))) {
            var report=new ReviewedReport("test.xml","UTF-8","test",java.util.List.of(),evidence);
            assertThatThrownBy(report::displayUnit).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new ReviewedReport("test.xml","UTF-8","test",java.util.List.of(),Map.of("displayUnit",1_000_000)).displayUnit()).isEqualTo(1_000_000);
        assertThat(new ReviewedReport("test.xml","UTF-8","test",java.util.List.of(),Map.of("displayUnit",1_000)).displayUnit()).isEqualTo(1_000);
    }
}
