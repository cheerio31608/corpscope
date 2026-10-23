package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.financial.StatementType;
import io.corpscope.ingestion.DataQualityException;
import java.io.*;
import java.nio.charset.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class Hyundai2016ReportParserTest {
    final Hyundai2016ReportParser parser=new Hyundai2016ReportParser();
    static String fixture() throws IOException {
        try (var in=Hyundai2016ReportParserTest.class.getResourceAsStream("/fixtures/hyundai-2016-report.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static byte[] zip(String receipt,String text,Charset charset) throws IOException {
        var out=new ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(receipt+".xml")); zip.write(text.getBytes(charset)); zip.closeEntry();
        }
        return out.toByteArray();
    }
    static String ofsFixture(boolean corrected) throws IOException {
        String name="/fixtures/hyundai-2016-ofs-"+(corrected ? "corrected":"original")+".xml";
        try (var in=Hyundai2016ReportParserTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void extractsReviewedSeparateTotalsWithoutUsingPriorYearOrConsolidatedValues(boolean corrected) throws Exception {
        String receipt=corrected ? "20220217000946":"20170331004820";
        String separate=ofsFixture(corrected);
        String mixed=fixture().replace("</DOCUMENT>",separate
                .substring(separate.indexOf("<TABLE-GROUP"),separate.indexOf("</DOCUMENT>"))+"</DOCUMENT>");
        var result=parser.parse(receipt,zip(receipt,mixed,StandardCharsets.UTF_8),StatementType.OFS);
        assertThat(result.profile()).isEqualTo(Hyundai2016ReportParser.OFS_PROFILE);
        assertThat(result.evidence()).containsEntry("basis","SEPARATE_TOTAL_OPERATIONS");
        assertThat(result.accounts()).hasSize(6);
        var values=result.accounts().stream().collect(java.util.stream.Collectors.toMap(ReviewedReport.Account::standardAccount,ReviewedReport.Account::amount));
        assertThat(values.get(StandardAccount.ASSETS)).isEqualByComparingTo(corrected ? "69737669000000":"69850917000000");
        assertThat(values.get(StandardAccount.LIABILITIES)).isEqualByComparingTo(corrected ? "17451676000000":"17271206000000");
        assertThat(values.get(StandardAccount.EQUITY)).isEqualByComparingTo(corrected ? "52285993000000":"52579711000000");
        assertThat(values.get(StandardAccount.REVENUE)).isEqualByComparingTo("41713632000000");
        assertThat(values.get(StandardAccount.OPERATING_PROFIT)).isEqualByComparingTo("2699482000000");
        assertThat(values.get(StandardAccount.NET_INCOME)).isEqualByComparingTo("4101860000000");
    }

    @ParameterizedTest @ValueSource(strings={"scope","year","unit","duplicate","amount","label"})
    void separateProfileRejectsUnreviewedLayout(String kind) throws Exception {
        String text=ofsFixture(true);
        text=switch(kind) {
            case "scope" -> text.replace("{XBRL}BS_S","{XBRL}BS");
            case "year" -> text.replace("2016.12.31","2015.12.31");
            case "unit" -> text.replace("백만원","천원");
            case "duplicate" -> text.replace("</DOCUMENT>",text.substring(text.indexOf("<TABLE-GROUP"),text.indexOf("</TABLE-GROUP>")+14)+"</DOCUMENT>");
            case "amount" -> text.replace("69,737,669","69,737,670");
            default -> text.replace("당기순이익","연결당기순이익");
        };
        byte[] bytes=zip("20220217000946",text,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse("20220217000946",bytes,StatementType.OFS)).isInstanceOf(DataQualityException.class);
    }
    @Test void selectsConsolidatedCurrentColumnAndPreservesPrecision() throws Exception {
        var report=parser.parse("20170331004820",zip("20170331004820",fixture(),StandardCharsets.UTF_8));
        assertThat(report.accounts()).hasSize(6);
        assertThat(report.accounts().stream().filter(a -> a.standardAccount()==StandardAccount.ASSETS).findFirst().orElseThrow().amount())
                .isEqualByComparingTo("178835928000000");
        assertThat(report.evidence()).containsEntry("scope","SIX_CURRENT_TOTALS_ONLY");
    }
    @Test void supportsActualLegacyCp949DespiteUtf8Declaration() throws Exception {
        var report=parser.parse("20170331004820",zip("20170331004820",fixture(),Charset.forName("MS949")));
        assertThat(report.encoding()).isEqualTo("MS949");
        assertThat(report.accounts()).hasSize(6);
    }
    @ParameterizedTest @ValueSource(strings={"unit","year","company","duplicate","amount","doctype"})
    void rejectsUnreviewedOrAmbiguousSource(String kind) throws Exception {
        String text=fixture();
        text=switch(kind) {
            case "unit" -> text.replace("백만원","천원");
            case "year" -> text.replace("2016.12.31","2017.12.31");
            case "company" -> text.replace("00164742","00000001");
            case "duplicate" -> text.replace("</DOCUMENT>",text.substring(text.indexOf("<TABLE-GROUP"),text.indexOf("</TABLE-GROUP>")+14)+"</DOCUMENT>");
            case "amount" -> text.replace("178,835,928","178,835,929");
            default -> "<!DOCTYPE DOCUMENT SYSTEM 'file:///secret'>"+text;
        };
        byte[] bytes=zip("20170331004820",text,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse("20170331004820",bytes)).isInstanceOf(DataQualityException.class);
    }
}
