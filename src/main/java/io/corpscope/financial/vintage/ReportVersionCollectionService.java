package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.external.dart.*;
import io.corpscope.financial.StatementType;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ReportVersionCollectionService {
    static final List<String> RECEIPTS=List.of("20170331004820","20220217000946");
    private final DartGateway gateway;
    private final Hyundai2016ReportParser parser;
    private final ReviewedReportStore store;

    public ReportVersionCollectionService(DartGateway gateway,Hyundai2016ReportParser parser,
            JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager transactions) {
        this.gateway=gateway; this.parser=parser; this.store=new ReviewedReportStore(jdbc,mapper,transactions);
    }

    public List<Map<String,Object>> collectPilot() {
        return collectPilot(StatementType.CFS);
    }

    public List<Map<String,Object>> collectPilot(StatementType statement) {
        if (!"OPENDART".equals(gateway.source())) throw new IllegalArgumentException("Use real-data profile for report versions");
        Map<String,byte[]> documents=new LinkedHashMap<>();
        for (String receipt:RECEIPTS) documents.put(receipt,gateway.document(receipt));
        return savePilot(documents,statement);
    }

    List<Map<String,Object>> savePilot(Map<String,byte[]> documents) {
        return savePilot(documents,StatementType.CFS);
    }

    List<Map<String,Object>> savePilot(Map<String,byte[]> documents,StatementType statement) {
        if (!documents.keySet().equals(Set.copyOf(RECEIPTS))) throw new IllegalArgumentException("Pilot requires both reviewed receipts");
        List<ReviewedReportStore.Input> inputs=new ArrayList<>();
        for(String receipt:RECEIPTS) {
            var report=parser.parse(receipt,documents.get(receipt),statement);
            LocalDate date=receipt.equals(RECEIPTS.getFirst()) ? LocalDate.of(2017,3,31):LocalDate.of(2022,2,17);
            inputs.add(new ReviewedReportStore.Input(2016,statement,receipt,date,documents.get(receipt),report));
        }
        return store.save("00164742",inputs);
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
