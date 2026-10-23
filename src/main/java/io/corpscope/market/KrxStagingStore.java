package io.corpscope.market;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KrxStagingStore {
    public record Result(long snapshotId,int rows,boolean inserted,String priceBasis,boolean forecastReady) {}
    private final JdbcTemplate jdbc;private final KrxSnapshotParser parser;
    public KrxStagingStore(JdbcTemplate jdbc,KrxSnapshotParser parser) {this.jdbc=jdbc;this.parser=parser;}
    @Transactional
    public Result save(String endpoint,LocalDate day,String expectedHash,Instant collectedAt,byte[] bytes) {
        if(expectedHash==null || !expectedHash.matches("[a-f0-9]{64}") || collectedAt==null || collectedAt.isAfter(Instant.now().plusSeconds(300)) ||
            bytes==null || !expectedHash.equals(hash(bytes))) throw new IllegalArgumentException("Invalid KRX archive hash or collection time");
        var quotes=parser.parse(endpoint,day,bytes);
        var inserted=jdbc.queryForList("""
            INSERT INTO krx_price_snapshot(endpoint,trading_date,payload_sha256,payload_utf8,collected_at,reported_row_count)
            VALUES (?,?,?,?,?,?) ON CONFLICT(endpoint,trading_date,payload_sha256) DO NOTHING RETURNING id
            """,Long.class,endpoint,day,expectedHash,bytes,Timestamp.from(collectedAt.truncatedTo(ChronoUnit.MICROS)),quotes.size());
        // Lock the same snapshot for concurrent/repeated imports; never replace another hash/version.
        var snapshots=jdbc.queryForList("""
            SELECT id,payload_utf8,reported_row_count FROM krx_price_snapshot
            WHERE endpoint=? AND trading_date=? AND payload_sha256=? FOR UPDATE
            """,endpoint,day,expectedHash);
        var snapshot=snapshots.getFirst();long id=((Number)snapshot.get("id")).longValue();
        if(!Arrays.equals((byte[])snapshot.get("payload_utf8"),bytes) || ((Number)snapshot.get("reported_row_count")).intValue()!=quotes.size())
            throw new IllegalArgumentException("Stored KRX original differs; manual review required");
        if(!inserted.isEmpty()) jdbc.batchUpdate("""
            INSERT INTO krx_price_quote_staging(snapshot_id,stock_code,stock_name,market,open_price,high_price,low_price,close_price,volume,listed_shares)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,quotes,500,(ps,q) -> {
                ps.setLong(1,id);ps.setString(2,q.code());ps.setString(3,q.name());ps.setString(4,q.market());
                ps.setBigDecimal(5,q.open());ps.setBigDecimal(6,q.high());ps.setBigDecimal(7,q.low());ps.setBigDecimal(8,q.close());
                ps.setBigDecimal(9,q.volume());ps.setBigDecimal(10,q.listedShares());
            });
        var stored=jdbc.query("""
            SELECT stock_code,stock_name,market,open_price,high_price,low_price,close_price,volume,listed_shares
            FROM krx_price_quote_staging WHERE snapshot_id=? ORDER BY stock_code
            """,(rs,n) -> new KrxSnapshotParser.Quote(rs.getString(1),rs.getString(2),rs.getString(3),canonical(rs.getBigDecimal(4)),
                canonical(rs.getBigDecimal(5)),canonical(rs.getBigDecimal(6)),canonical(rs.getBigDecimal(7)),canonical(rs.getBigDecimal(8)),canonical(rs.getBigDecimal(9))),id);
        if(!stored.equals(quotes)) throw new IllegalArgumentException("Stored KRX staging values differ; no silent repair");
        return new Result(id,quotes.size(),!inserted.isEmpty(),"UNADJUSTED",false);
    }
    public static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException ex) {throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static BigDecimal canonical(BigDecimal n) {return n==null ? null:n.stripTrailingZeros();}
}
