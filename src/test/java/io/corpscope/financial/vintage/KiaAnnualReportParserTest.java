package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DataQualityException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KiaAnnualReportParserTest {
    private static final String RECEIPT="20250313001390";
    private final KiaAnnualReportParser parser=new KiaAnnualReportParser();

    @Test void extractsOnlyKiaConsolidatedTotalsAndRejectsAlteredUnitOrContext() throws Exception {
        String xml=fixture();
        var parsed=parser.parse(RECEIPT,archive(xml));
        assertThat(parsed.current().displayUnit()).isEqualTo(1_000_000);
        assertThat(parsed.current().accounts()).hasSize(6);
        assertThat(parsed.current().accounts().stream()
            .filter(a -> a.standardAccount()==StandardAccount.REVENUE).findFirst().orElseThrow().amount())
            .isEqualByComparingTo("107448752000000");
        assertThat(parsed.comparativeTotals().get(StandardAccount.REVENUE))
            .isEqualByComparingTo("99808420000000");
        assertThatThrownBy(() -> parser.parse(RECEIPT,archive(xml.replace("단위:백만원","단위:원"))))
            .isInstanceOf(DataQualityException.class);
        assertThatThrownBy(() -> parser.parse(RECEIPT,archive(xml.replaceFirst(
            "CFY2024eFY_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember",
            "CFY2025eFY_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember"))))
            .isInstanceOf(DataQualityException.class);
    }

    private static String fixture() {
        StringBuilder xml=new StringBuilder("<ROOT><COMPANY-NAME AREGCIK=\"00106641\">기아</COMPANY-NAME>");
        table(xml,"{XBRL}BS_C","연결재무상태표 2024.12.31 현재 2023.12.31 현재 (단위:백만원)",true,
            new String[][]{{"자산총계","ifrs-full_Assets","92755874","80627826"},
                {"부채총계","ifrs-full_Liabilities","36915565","34069591"},
                {"자본총계","ifrs-full_Equity","55840309","46558235"}});
        table(xml,"{XBRL}IS_C2","연결손익계산서 2024.01.01부터 2024.12.31까지 2023.12.31까지 (단위:백만원)",false,
            new String[][]{{"매출액","ifrs-full_Revenue","107448752","99808420"},
                {"영업이익","dart_OperatingIncomeLoss","12667139","11607873"},
                {"당기순이익","ifrs-full_ProfitLoss","9775005","8777817"}});
        return xml.append("</ROOT>").toString();
    }

    private static void table(StringBuilder xml,String code,String header,boolean balance,String[][] rows) {
        xml.append("<TABLE-GROUP ACLASS=\"").append(code).append("\"><TABLE>").append(header).append("</TABLE>");
        String suffix=balance ? "eFY":"dFY";
        for(String[] row:rows) {
            xml.append("<TR><TE>").append(row[0]).append("</TE>");
            for(int i=0;i<3;i++) {
                String context=(i==0 ? "CFY2024":i==1 ? "PFY2023":"BPFY2022")+suffix+
                    "_ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis_ifrs-full_ConsolidatedMember";
                xml.append("<TE ACODE=\"").append(row[1]).append("\" ACONTEXT=\"").append(context)
                    .append("\" ADECIMAL=\"-6\">").append(i==0 ? row[2]:row[3]).append("</TE>");
            }
            xml.append("</TR>");
        }
        xml.append("</TABLE-GROUP>");
    }

    private static byte[] archive(String xml) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(RECEIPT+".xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
