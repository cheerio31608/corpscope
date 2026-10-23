package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DataQualityException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SamsungAnnualReportParserTest {
    static String fixture(int year) throws IOException {
        try(var in=SamsungAnnualReportParserTest.class.getResourceAsStream("/fixtures/samsung-"+year+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static byte[] archive(int year) throws IOException {
        return Hyundai2016ReportParserTest.zip(year==2024 ? "20250311001085":"20260310002820",fixture(year),StandardCharsets.UTF_8);
    }
    @ParameterizedTest @ValueSource(ints={2024,2025})
    void extractsCurrentTotalsAndSeparatesComparativeColumn(int year) throws Exception {
        var result=new SamsungAnnualReportParser().parse(year==2024 ? "20250311001085":"20260310002820",archive(year));
        var amounts=result.current().accounts().stream().collect(java.util.stream.Collectors.toMap(ReviewedReport.Account::standardAccount,ReviewedReport.Account::amount));
        assertThat(amounts).hasSize(6);
        assertThat(amounts.get(StandardAccount.REVENUE)).isEqualByComparingTo(year==2024 ? "300870903000000":"333605938000000");
        assertThat(amounts.get(StandardAccount.NET_INCOME)).isEqualByComparingTo(year==2024 ? "34451351000000":"45206805000000");
        assertThat(amounts.get(StandardAccount.ASSETS)).isEqualByComparingTo(year==2024 ? "514531948000000":"566942110000000");
        assertThat(result.comparativeTotals().get(StandardAccount.REVENUE)).isEqualByComparingTo(year==2024 ? "258935494000000":"300870903000000");
        assertThat(result.current().profile()).isEqualTo("SAMSUNG_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1");
    }
    @org.junit.jupiter.api.Test
    void extractsReviewedLegacyNamesWithoutPretendingTheyHaveXbrlCodes() throws Exception {
        String receipt="20240312000736";
        var result=new SamsungAnnualReportParser().parse(receipt,
            Hyundai2016ReportParserTest.zip(receipt,fixture(2023),StandardCharsets.UTF_8));
        var amounts=result.current().accounts().stream().collect(java.util.stream.Collectors.toMap(ReviewedReport.Account::standardAccount,ReviewedReport.Account::amount));
        assertThat(amounts).hasSize(6);
        assertThat(amounts.get(StandardAccount.REVENUE)).isEqualByComparingTo("258935494000000");
        assertThat(amounts.get(StandardAccount.OPERATING_PROFIT)).isEqualByComparingTo("6566976000000");
        assertThat(amounts.get(StandardAccount.NET_INCOME)).isEqualByComparingTo("15487100000000");
        assertThat(amounts.get(StandardAccount.ASSETS)).isEqualByComparingTo("455905980000000");
        assertThat(amounts.get(StandardAccount.LIABILITIES)).isEqualByComparingTo("92228115000000");
        assertThat(amounts.get(StandardAccount.EQUITY)).isEqualByComparingTo("363677865000000");
        assertThat(result.comparativeTotals().get(StandardAccount.REVENUE)).isEqualByComparingTo("302231360000000");
        assertThat(result.current().profile()).isEqualTo("SAMSUNG_2023_ANNUAL_CFS_SIX_TOTALS_V1");
        var next=new SamsungAnnualReportParser().parse("20250311001085",archive(2024));
        assertThat(result.current().accounts()).allSatisfy(a ->
            assertThat(a.amount()).isEqualByComparingTo(next.comparativeTotals().get(a.standardAccount())));
    }

    @ParameterizedTest @ValueSource(ints={2020,2021,2022})
    void readsReviewedPlainTdTotalsWithExactYearProfile(int year) throws Exception {
        String receipt=year==2020 ? "20210309000744":year==2021 ? "20220308000798":"20230307000542";
        var encoding=year<=2021 ? java.nio.charset.Charset.forName("MS949"):StandardCharsets.UTF_8;
        var result=new SamsungAnnualReportParser().parse(receipt,
            Hyundai2016ReportParserTest.zip(receipt,fixture(year),encoding));
        var amounts=result.current().accounts().stream().collect(java.util.stream.Collectors.toMap(ReviewedReport.Account::standardAccount,ReviewedReport.Account::amount));
        assertThat(amounts).hasSize(6);
        assertThat(amounts.get(StandardAccount.REVENUE)).isEqualByComparingTo(year==2020 ? "236806988000000":year==2021 ? "279604799000000":"302231360000000");
        assertThat(amounts.get(StandardAccount.NET_INCOME)).isEqualByComparingTo(year==2020 ? "26407832000000":year==2021 ? "39907450000000":"55654077000000");
        assertThat(amounts.get(StandardAccount.ASSETS)).isEqualByComparingTo(year==2020 ? "378235718000000":year==2021 ? "426621158000000":"448424507000000");
        assertThat(result.current().profile()).isEqualTo("SAMSUNG_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1");
        String nextReceipt=year==2020 ? "20220308000798":year==2021 ? "20230307000542":"20240312000736";
        var next=new SamsungAnnualReportParser().parse(nextReceipt,
            Hyundai2016ReportParserTest.zip(nextReceipt,fixture(year+1),StandardCharsets.UTF_8));
        assertThat(result.current().accounts()).allSatisfy(account ->
            assertThat(account.amount()).isEqualByComparingTo(next.comparativeTotals().get(account.standardAccount())));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> oldTableMutations() {
        return java.util.stream.Stream.of(2020,2021,2022).flatMap(year -> java.util.stream.Stream.of(
            "unit","group","name","tag","period","duplicate","balance","attribute").map(kind ->
                org.junit.jupiter.params.provider.Arguments.of(year,kind)));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("oldTableMutations")
    void rejectsWrongScopeAndMeaningInPlainTdReports(int year,String kind) throws Exception {
        String s=fixture(year);
        s=switch(kind) {
            case "unit" -> s.replace("백만원","천원");
            case "group" -> s.replace("{XBRL}BS","{XBRL}BS_C");
            case "name" -> s.replace("수익(매출액)","기타수익");
            case "tag" -> s.replace("<TD ","<TE ").replace("</TD>","</TE>");
            case "period" -> s.replace((year-1)+".12.31",(year-3)+".12.31");
            case "balance" -> s.replace(year==2020 ? "378,235,718":year==2021 ? "426,621,158":"448,424,507","999,999,999");
            case "attribute" -> s.replace("<TD ALIGN=","<TD ACODE=\"ifrs-full_Assets\" ALIGN=");
            default -> s.replace("</DOCUMENT>",s.substring(s.indexOf("<TABLE-GROUP"),s.indexOf("</TABLE-GROUP>")+14)+"</DOCUMENT>");
        };
        String receipt=year==2020 ? "20210309000744":year==2021 ? "20220308000798":"20230307000542";
        byte[] zip=Hyundai2016ReportParserTest.zip(receipt,s,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new SamsungAnnualReportParser().parse(receipt,zip)).isInstanceOf(DataQualityException.class);
    }

    @ParameterizedTest @ValueSource(strings={"unit","company","group","year","revenue","net-income","balance","attribute","duplicate","previous-year"})
    void rejectsAmbiguousLegacyTotals(String kind) throws Exception {
        String s=fixture(2023);
        s=switch(kind) {
            case "unit" -> s.replace("백만원","천원");
            case "company" -> s.replace("00126380","00164742");
            case "group" -> s.replace("{XBRL}BS_C","{XBRL}BS_S");
            case "year" -> s.replace("2023.12.31","2022.12.31");
            case "previous-year" -> s.replace("2022.12.31","2020.12.31");
            case "revenue" -> s.replace("영업수익","기타수익");
            case "net-income" -> s.replace("당기순이익(손실)","지배주주순이익(손실)");
            case "balance" -> s.replace("455,905,980","455,905,981");
            case "attribute" -> s.replace("<TE ALIGN=", "<TE ACODE=\"ifrs-full_Assets\" ALIGN=");
            default -> s.replace("</DOCUMENT>",s.substring(s.indexOf("<TABLE-GROUP"),s.indexOf("</TABLE-GROUP>")+14)+"</DOCUMENT>");
        };
        byte[] zip=Hyundai2016ReportParserTest.zip("20240312000736",s,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new SamsungAnnualReportParser().parse("20240312000736",zip)).isInstanceOf(DataQualityException.class);
    }

    @ParameterizedTest @ValueSource(strings={"context","scale","negated","label","unit","company","group","year","amount","duplicate"})
    void rejectsUnreviewedOrAmbiguousMeaning(String kind) throws Exception {
        String s=fixture(2025);
        s=switch(kind) {
            case "context" -> s.replace("CFY2025","PFY2024");
            case "scale" -> s.replace("ADECIMAL=\"-6\"","ADECIMAL=\"-3\"");
            case "negated" -> s.replace("ANEGATED=\"N\"","ANEGATED=\"Y\"");
            case "label" -> s.replace("당기순이익","지배주주순이익");
            case "unit" -> s.replace("백만원","천원");
            case "company" -> s.replace("00126380","00164742");
            case "group" -> s.replace("{XBRL}BS_C","{XBRL}BS_S");
            case "year" -> s.replace("2025.12.31","2024.12.31");
            case "amount" -> s.replace("566,942,110","566,942,111");
            default -> s.replace("</DOCUMENT>",s.substring(s.indexOf("<TABLE-GROUP"),s.indexOf("</TABLE-GROUP>")+14)+"</DOCUMENT>");
        };
        byte[] zip=Hyundai2016ReportParserTest.zip("20260310002820",s,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new SamsungAnnualReportParser().parse("20260310002820",zip)).isInstanceOf(DataQualityException.class);
    }
}
