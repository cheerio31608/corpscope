package io.corpscope.financial.vintage;

import io.corpscope.ingestion.DataQualityException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SteelAnnualReportParserTest {
    private static final String RECEIPT="20250317000668";
    private final SteelAnnualReportParser parser=new SteelAnnualReportParser();

    @Test void acceptsExplicitWonUnitAndRejectsChangedContextOrUnit() throws Exception {
        String xml=fixture();
        var parsed=parser.parse(RECEIPT,archive(xml));
        assertThat(parsed.current().displayUnit()).isEqualTo(1);
        assertThat(parsed.current().accounts()).hasSize(6);
        assertThat(parsed.comparativeTotals()).hasSize(6);

        assertThatThrownBy(() -> parser.parse(RECEIPT,archive(xml.replaceFirst(
            "CFY2024eFY_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember",
            "CFY2025eFY_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember"))))
            .isInstanceOf(DataQualityException.class);
        assertThatThrownBy(() -> parser.parse(RECEIPT,archive(xml.replace("단위:원","단위:백만원"))))
            .isInstanceOf(DataQualityException.class);
    }

    private static String fixture() {
        StringBuilder xml=new StringBuilder("<ROOT><COMPANY-NAME AREGCIK=\"00145880\">현대제철 주식회사</COMPANY-NAME>");
        table(xml,"{XBRL}BS_C","연결재무상태표 제60기 2024.12.31 현재 제59기 2023.12.31 현재 (단위:원)",true,
            new String[][]{{"자산총계","ifrs-full_Assets","30","25"},
                {"부채총계","ifrs-full_Liabilities","10","8"},
                {"자본총계","ifrs-full_Equity","20","17"}});
        table(xml,"{XBRL}IS_C1","연결포괄손익계산서 제60기 2024.01.01부터 2024.12.31까지 제59기 2023.12.31까지 (단위:원)",false,
            new String[][]{{"매출액 (주29,38,41)","ifrs-full_Revenue","12","11"},
                {"영업이익 (주41)","dart_OperatingIncomeLoss","3","2"},
                {"당기순손익","ifrs-full_ProfitLoss","1","1"}});
        return xml.append("</ROOT>").toString();
    }
    private static void table(StringBuilder xml,String group,String header,boolean balance,String[][] rows) {
        xml.append("<TABLE-GROUP ACLASS=\"").append(group).append("\"><TABLE>").append(header).append("</TABLE>");
        String suffix=balance ? "eFY":"dFY";
        for(String[] row:rows) {
            xml.append("<TR><TE>").append(row[0]).append("</TE>");
            for(int i=0;i<3;i++) {
                String context=(i==0 ? "CFY2024":i==1 ? "PFY2023":"BPFY2022")+suffix+
                    "_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
                xml.append("<TE ACODE=\"").append(row[1]).append("\" ACONTEXT=\"").append(context)
                    .append("\" ADECIMAL=\"0\">").append(i==0 ? row[2]:row[3]).append("</TE>");
            }
            xml.append("</TR>");
        }
        xml.append("</TABLE-GROUP>");
    }
    private static byte[] archive(String xml) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(RECEIPT+".xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
