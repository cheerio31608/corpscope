package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DataQualityException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class HynixAnnualReportParserTest {
    static String fixture(int year) throws Exception {
        try(var in=HynixAnnualReportParserTest.class.getResourceAsStream("/fixtures/hynix-"+year+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @org.junit.jupiter.api.Test void reads2022TdCellsWithExactOldLabels() throws Exception {
        var parsed=new HynixAnnualReportParser().parse("20230321001209",Hyundai2016ReportParserTest.zip("20230321001209",fixture(2022),StandardCharsets.UTF_8));
        assertThat(parsed.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("2241669000000"));
        assertThat(parsed.current().evidence().toString()).doesNotContain("ACODE=", "ACONTEXT=", "ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"label","unit","column","group","tag","balance","attribute"})
    void rejectsUnreviewed2022Layouts(String kind) throws Exception {
        String original=fixture(2022);
        String changed=switch(kind) {
            case "label" -> original.replace("당기순이익","지배기업소유주순이익");
            case "unit" -> original.replace("백만원","천원");
            case "column" -> original.replace("제 74 기","제 73 기");
            case "group" -> original.replace("{XBRL}BS", "{XBRL}BS_S");
            case "tag" -> original.replace("<TD", "<TE").replace("</TD>","</TE>");
            case "balance" -> original.replace("103,871,512","103,871,513");
            default -> original.replace("<TD ALIGN=\"RIGHT\"", "<TD ACODE=\"ifrs-full_Assets\" ALIGN=\"RIGHT\"");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20230321001209",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new HynixAnnualReportParser().parse("20230321001209",zip)).isInstanceOf(DataQualityException.class);
    }
    @ParameterizedTest @ValueSource(ints={2024,2025})
    void usesTotalProfitRatherThanComprehensiveOrOwnerProfit(int year) throws Exception {
        String receipt=year==2024 ? "20250319000665":"20260317000635";
        var result=new HynixAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture(year),StandardCharsets.UTF_8));
        assertThat(result.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo(year==2024 ? "19796902000000":"42947902000000"));
        assertThat(result.comparativeTotals().get(StandardAccount.NET_INCOME)).isEqualByComparingTo(year==2024 ? "-9137547000000":"19796902000000");
    }
    @org.junit.jupiter.api.Test void reads2023PlainCellsWithoutInventingAccountCodes() throws Exception {
        var parsed=new HynixAnnualReportParser().parse("20240319000684",Hyundai2016ReportParserTest.zip("20240319000684",fixture(2023),StandardCharsets.UTF_8));
        assertThat(parsed.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("-9137547000000"));
        assertThat(parsed.comparativeTotals().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("2241669000000");
        assertThat(parsed.current().evidence().toString()).doesNotContain("ACODE=", "ACONTEXT=", "ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","date","column","owner-profit","balance","missing","unexpected-context"})
    void rejectsUnreviewed2023PlainTableLayouts(String kind) throws Exception {
        String original=fixture(2023);
        String changed=switch(kind) {
            case "company" -> original.replace("00164779","00159193");
            case "unit" -> original.replace("백만원","천원");
            case "date" -> original.replace("2023.12.31","2022.12.31");
            case "column" -> original.replace("제 75 기","제 74 기");
            case "owner-profit" -> original.replace("당기순이익(손실)","지배기업 소유주 순이익");
            case "balance" -> original.replace("100,330,165","100,330,166");
            case "missing" -> original.replace("32,765,719","");
            default -> original.replace("<TE ALIGN=\"RIGHT\"","<TE ACONTEXT=\"CFY2024\" ALIGN=\"RIGHT\"");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20240319000684",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new HynixAnnualReportParser().parse("20240319000684",zip)).isInstanceOf(DataQualityException.class);
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","context","group","net-income","balance","scale","negated"})
    void rejectsDifferentIdentityPeriodOrProfitMeaning(String kind) throws Exception {
        String s=fixture(2025);
        s=switch(kind) {
            case "company" -> s.replace("00164779","00126380");
            case "unit" -> s.replace("백만원","천원");
            case "context" -> s.replace("CFY2025","PFY2024");
            case "group" -> s.replace("{XBRL}IS_C1","{XBRL}IS_C2");
            case "net-income" -> s.replace("당기순이익(손실)","총포괄이익");
            case "balance" -> s.replace("176,107,659","176,107,660");
            case "scale" -> s.replace("ADECIMAL=\"-6\"","ADECIMAL=\"-3\"");
            default -> s.replace("ADECIMAL=\"-6\"","ADECIMAL=\"-6\" ANEGATED=\"Y\"");
        };
        byte[] zip=Hyundai2016ReportParserTest.zip("20260317000635",s,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new HynixAnnualReportParser().parse("20260317000635",zip)).isInstanceOf(DataQualityException.class);
    }
}
