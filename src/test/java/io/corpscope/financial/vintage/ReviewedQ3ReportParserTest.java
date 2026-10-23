package io.corpscope.financial.vintage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewedQ3ReportParserTest {
    private static final String CORP="00266961", RECEIPT="20251114001436";
    private static final String AXIS="_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
    private final ReviewedQ3ReportParser parser=new ReviewedQ3ReportParser();

    @Test void wonUnitAndCombinedIncomeStatementKeepTwoPeriodsDistinct() throws Exception {
        var parsed=parser.parse(CORP,RECEIPT,zip(xml()));
        assertThat(parsed.accounts()).hasSize(6);
        var revenue=parsed.accounts().stream().filter(a -> a.standardAccount().name().equals("REVENUE")).findFirst().orElseThrow();
        assertThat(revenue.statementDivision()).isEqualTo("CIS");
        assertThat(revenue.displayUnit()).isEqualTo(1);
        assertThat(revenue.amount()).isEqualByComparingTo("2");
        assertThat(revenue.cumulativeAmount()).isEqualByComparingTo("5");
    }

    @Test void falseSourceScaleDoesNotSilentlyBecomeOneWon() throws Exception {
        assertThatThrownBy(() -> parser.parse(CORP,RECEIPT,zip(xml().replace("ADECIMAL='0'","ADECIMAL='-6'"))))
            .hasMessageContaining("source scale mismatch");
    }

    private static String xml() {
        return "<DOCUMENT><COMPANY-NAME AREGCIK='"+CORP+"'>Synthetic fixture</COMPANY-NAME>"
            +"<TABLE-GROUP ACLASS='{XBRL}BS_C'><TITLE>연결 재무상태표 2025.09.30 (단위 : 원)</TITLE><TABLE>"
            +bs("자산총계","ifrs-full_Assets",100)+bs("부채총계","ifrs-full_Liabilities",30)
            +bs("자본총계","ifrs-full_Equity",70)+"</TABLE></TABLE-GROUP>"
            +"<TABLE-GROUP ACLASS='{XBRL}IS_C1'><TITLE>연결 포괄손익계산서 2025.09.30 (단위 : 원) 3개월 누적</TITLE><TABLE>"
            +income("영업수익 (주5)","ifrs-full_Revenue",2,5)
            +income("영업이익","dart_OperatingIncomeLoss",1,3)
            +income("분기순이익","ifrs-full_ProfitLoss",1,2)
            +"</TABLE></TABLE-GROUP></DOCUMENT>";
    }
    private static String bs(String label,String code,int amount) {
        return "<TR><TE>"+label+"</TE><TE ACODE='"+code+"' ACONTEXT='CFY2025eTQA"+AXIS
            +"' ADECIMAL='0' ANEGATED='N'>"+amount+"</TE><TE>0</TE></TR>";
    }
    private static String income(String label,String code,int amount,int cumulative) {
        return "<TR><TE>"+label+"</TE><TE ACODE='"+code+"' ACONTEXT='CFY2025dTQQ"+AXIS
            +"' ADECIMAL='0' ANEGATED='N'>"+amount+"</TE><TE ACODE='"+code
            +"' ACONTEXT='CFY2025dTQA"+AXIS+"' ADECIMAL='0' ANEGATED='N'>"+cumulative
            +"</TE><TE>0</TE><TE>0</TE></TR>";
    }
    private static byte[] zip(String xml) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry(RECEIPT+".xml"));
            out.write(xml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
