package io.corpscope.market;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class KrxSnapshotParserTest {
    static final ObjectMapper MAPPER=new ObjectMapper();
    static ObjectNode row(String code,String close) {
        var r=MAPPER.createObjectNode();r.put("BAS_DD","20250319");r.put("ISU_CD",code);r.put("ISU_NM","Synthetic quote");r.put("MKT_NM","KOSPI");
        r.put("TDD_OPNPRC","10");r.put("TDD_HGPRC","15");r.put("TDD_LWPRC","9");r.put("TDD_CLSPRC",close);
        r.put("ACC_TRDVOL","1,000");r.put("LIST_SHRS","100000");return r;
    }
    static byte[] bytes(ObjectNode... rows) throws Exception {
        var root=MAPPER.createObjectNode();var list=root.putArray("OutBlock_1");for(var row:rows) list.add(row);return MAPPER.writeValueAsBytes(root);
    }
    @Test void preservesLeadingZeroNullAndZeroWithoutAdjustment() throws Exception {
        var row=row("005930","12.5");row.put("TDD_OPNPRC","-");row.put("TDD_HGPRC","");row.put("TDD_LWPRC","0");
        var quotes=new KrxSnapshotParser(MAPPER).parse("stk_bydd_trd",LocalDate.of(2025,3,19),bytes(row));
        assertThat(quotes.getFirst().code()).isEqualTo("005930");assertThat(quotes.getFirst().open()).isNull();
        assertThat(quotes.getFirst().high()).isNull();assertThat(quotes.getFirst().low()).isZero();
        assertThat(quotes.getFirst().close()).isEqualByComparingTo("12.5");
        assertThat(quotes.getFirst().volume()).isEqualByComparingTo("1000");
    }
    @ParameterizedTest @ValueSource(strings={"date","market","code","negative","bad-comma","scientific","fractional-volume","overflow","missing-field","duplicate"})
    void rejectsAmbiguousIdentityOrNumbers(String kind) throws Exception {
        var row=row("005930","12");
        switch(kind) {
            case "date" -> row.put("BAS_DD","20250320");case "market" -> row.put("MKT_NM","KOSDAQ");
            case "code" -> row.put("ISU_CD","5930");case "negative" -> row.put("TDD_CLSPRC","-12");
            case "bad-comma" -> row.put("TDD_CLSPRC","1,00");case "scientific" -> row.put("TDD_CLSPRC","1e2");
            case "fractional-volume" -> row.put("ACC_TRDVOL","1.5");case "overflow" -> row.put("TDD_CLSPRC","9".repeat(31));
            case "missing-field" -> row.remove("TDD_OPNPRC");default -> {}
        }
        byte[] input=kind.equals("duplicate") ? bytes(row,row):bytes(row);
        assertThatThrownBy(() -> new KrxSnapshotParser(MAPPER).parse("stk_bydd_trd",LocalDate.of(2025,3,19),input))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyMarketResponseIsStoredAsEmptyNotDeclaredHoliday() throws Exception {
        assertThat(new KrxSnapshotParser(MAPPER).parse("stk_bydd_trd",LocalDate.of(2025,3,19),bytes())).isEmpty();
    }
}
