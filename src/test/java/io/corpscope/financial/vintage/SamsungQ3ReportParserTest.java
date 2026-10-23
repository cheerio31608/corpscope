package io.corpscope.financial.vintage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SamsungQ3ReportParserTest {
    private final SamsungQ3ReportParser parser=new SamsungQ3ReportParser();
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";

    @Test void sourceContextsKeepThreeMonthSeparateFromCumulative() throws Exception {
        var report=parser.parse(SamsungQ3ReportParser.RECEIPT,zip(xml()));
        assertThat(report.accounts()).hasSize(6);
        var revenue=report.accounts().stream().filter(a -> a.standardAccount().name().equals("REVENUE")).findFirst().orElseThrow();
        assertThat(revenue.amount()).isEqualByComparingTo("2000000");
        assertThat(revenue.cumulativeAmount()).isEqualByComparingTo("5000000");
        var assets=report.accounts().stream().filter(a -> a.standardAccount().name().equals("ASSETS")).findFirst().orElseThrow();
        assertThat(assets.amount()).isEqualByComparingTo("100000000");
        assertThat(assets.cumulativeAmount()).isNull();
    }

    @Test void swappedCumulativeContextCannotBeSavedAsQuarterAmount() throws Exception {
        String altered=xml().replace("CFY2025dTQA"+AXIS,"PFY2024dTQA"+AXIS);
        assertThatThrownBy(() -> parser.parse(SamsungQ3ReportParser.RECEIPT,zip(altered)))
            .hasMessageContaining("context or scale mismatch");
    }

    private static String xml() {
        return "<DOCUMENT><COMPANY-NAME AREGCIK='00126380'>Synthetic fixture</COMPANY-NAME>"
            +"<TABLE-GROUP ACLASS='{XBRL}BS_C'><TITLE>연결 재무상태표 2025.09.30 (단위 : 백만원)</TITLE><TABLE>"
            +bs("자산총계","ifrs-full_Assets",100)
            +bs("부채총계","ifrs-full_Liabilities",30)
            +bs("자본총계","ifrs-full_Equity",70)
            +"</TABLE></TABLE-GROUP>"
            +"<TABLE-GROUP ACLASS='{XBRL}IS_C2'><TITLE>연결 손익계산서 2025.09.30 (단위 : 백만원) 3개월 누적</TITLE><TABLE>"
            +is("매출액 (주27)","ifrs-full_Revenue",2,5)
            +is("영업이익 (주27)","dart_OperatingIncomeLoss",1,3)
            +is("분기순이익","ifrs-full_ProfitLoss",1,2)
            +"</TABLE></TABLE-GROUP></DOCUMENT>";
    }
    private static String bs(String label,String code,int amount) {
        return "<TR><TE>"+label+"</TE><TE ACODE='"+code+"' ACONTEXT='CFY2025eTQA"+AXIS
            +"' ADECIMAL='-6' ANEGATED='N'>"+amount+"</TE><TE>0</TE></TR>";
    }
    private static String is(String label,String code,int amount,int cumulative) {
        return "<TR><TE>"+label+"</TE><TE ACODE='"+code+"' ACONTEXT='CFY2025dTQQ"+AXIS
            +"' ADECIMAL='-6' ANEGATED='N'>"+amount+"</TE><TE ACODE='"+code
            +"' ACONTEXT='CFY2025dTQA"+AXIS+"' ADECIMAL='-6' ANEGATED='N'>"+cumulative
            +"</TE><TE>0</TE><TE>0</TE></TR>";
    }
    private static byte[] zip(String xml) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry(SamsungQ3ReportParser.RECEIPT+".xml"));
            out.write(xml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
