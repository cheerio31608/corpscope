package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ReviewedReport(String mainEntry, String encoding, String profile,
        List<Account> accounts, Map<String,Object> evidence) {
    /** Original display scale; amounts have already been normalized to KRW by the parser. */
    public long displayUnit() {
        Object unit=evidence.get("displayUnit");
        // The original Hyundai profile records the scale on each table, not at the root.
        // Read that explicit evidence without changing previously stored evidence JSON.
        if(unit==null && evidence.get("tables") instanceof List<?> tables && !tables.isEmpty()) {
            Object candidate=null;
            for(Object table:tables) {
                if(!(table instanceof Map<?,?> fields) || !(fields.get("displayUnit") instanceof Number scale))
                    throw new IllegalArgumentException("Missing reviewed table display unit");
                if(candidate!=null && !candidate.toString().equals(scale.toString()))
                    throw new IllegalArgumentException("Mixed reviewed table display units");
                candidate=scale;
            }
            unit=candidate;
        }
        if(!(unit instanceof Number)) throw new IllegalArgumentException("Missing reviewed display unit");
        BigDecimal value=new BigDecimal(unit.toString());
        if(value.compareTo(BigDecimal.ONE)==0) return 1;
        if(value.compareTo(BigDecimal.valueOf(1_000))==0) return 1_000;
        if(value.compareTo(BigDecimal.valueOf(1_000_000))==0) return 1_000_000;
        throw new IllegalArgumentException("Unsupported reviewed display unit");
    }
    public record Account(StandardAccount standardAccount, String rawAccountName,
                          String statementDivision, BigDecimal amount) {}
}
