package io.corpscope.market;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

/** Official response -> staging numbers. No adjustment, currency, publication-time or holiday inference. */
@Component
public class KrxSnapshotParser {
    public record Quote(String code,String name,String market,BigDecimal open,BigDecimal high,BigDecimal low,
        BigDecimal close,BigDecimal volume,BigDecimal listedShares) {}
    private final ObjectMapper mapper;
    public KrxSnapshotParser(ObjectMapper mapper) {this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
    public List<Quote> parse(String endpoint,LocalDate day,byte[] payload) {
        if(day==null || endpoint==null || !Set.of("stk_bydd_trd","ksq_bydd_trd").contains(endpoint) || payload==null || payload.length>5*1024*1024)
            throw invalid();
        JsonNode root;
        try {
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString();
            root=mapper.readTree(text);
        } catch(Exception ex) {throw invalid();}
        if(root==null || !root.isObject() || !root.path("OutBlock_1").isArray() || root.path("OutBlock_1").size()>20000) throw invalid();
        List<Quote> quotes=new ArrayList<>();Set<String> codes=new HashSet<>();
        for(var row:root.path("OutBlock_1")) {
            String code=text(row,"ISU_CD"),name=text(row,"ISU_NM"),market=text(row,"MKT_NM");
            if(!text(row,"BAS_DD").equals(day.format(DateTimeFormatter.BASIC_ISO_DATE)) || !code.matches("[A-Z0-9]{6}") ||
                !codes.add(code) || name.isBlank() || name.length()>300 ||
                !market.equals(endpoint.equals("stk_bydd_trd") ? "KOSPI":"KOSDAQ")) throw invalid();
            quotes.add(new Quote(code,name,market,amount(row,"TDD_OPNPRC",false),amount(row,"TDD_HGPRC",false),
                amount(row,"TDD_LWPRC",false),amount(row,"TDD_CLSPRC",false),amount(row,"ACC_TRDVOL",true),amount(row,"LIST_SHRS",true)));
        }
        return quotes.stream().sorted(Comparator.comparing(Quote::code)).toList();
    }
    private static String text(JsonNode row,String name) {
        if(!row.path(name).isTextual()) throw invalid();return row.path(name).textValue().trim();
    }
    private static BigDecimal amount(JsonNode row,String field,boolean integer) {
        String s=text(row,field);
        if(s.isEmpty() || s.equals("-")) return null;
        if(!s.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:[.][0-9]+)?")) throw invalid();
        var n=new BigDecimal(s.replace(",","")).stripTrailingZeros();
        if(n.signum()<0 || n.scale()>8 || n.precision()-n.scale()>(integer ? 38:30) || (integer && n.scale()>0)) throw invalid();
        return n;
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("KRX response structure/date/identity or number failed validation");}
}
