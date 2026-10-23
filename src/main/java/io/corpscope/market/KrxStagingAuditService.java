package io.corpscope.market;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Administrative audit of observed responses, never proof of holidays or model-ready prices. */
@Service
public class KrxStagingAuditService {
    public record Request(LocalDate from,LocalDate through,List<String> stockCodes) {}
    public record SharesChange(String stockCode,LocalDate previousDate,BigDecimal previousShares,LocalDate date,BigDecimal shares) {}
    public record Company(String stockCode,int observedRows,List<LocalDate> missingOnNonemptyDates,
        List<LocalDate> unavailableOhlcOrVolume,List<SharesChange> shareCountChanges) {}
    public record Report(LocalDate from,LocalDate through,int requestedCalendarDays,int storedResponseDates,
        List<LocalDate> missingResponseDates,List<LocalDate> emptyResponseDates,int observedNonemptyDates,
        int multipleSnapshotDates,long stagedQuotes,List<Company> companies,int approvedTrainingRows,
        boolean forecastReady,KrxEvidenceReview.CalendarCheck calendarDateReview,
        List<KrxEvidenceReview.ReviewedEvent> reviewedEvents,KrxEvidenceReview.WindowCoverage dateWindowCoverage,
        KrxEvidenceReview.MarketEvidence partialMarketEvidence,
        List<String> blockers,List<String> limitations) {}
    private final JdbcTemplate jdbc;
    private final KrxEvidenceReview evidence;
    public KrxStagingAuditService(JdbcTemplate jdbc,KrxEvidenceReview evidence) {this.jdbc=jdbc;this.evidence=evidence;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report audit(Request request) {
        if(request==null || request.from()==null || request.through()==null || request.stockCodes()==null ||
            request.through().isBefore(request.from()) || ChronoUnit.DAYS.between(request.from(),request.through())>=366 ||
            request.stockCodes().isEmpty() || request.stockCodes().size()>50 ||
            request.stockCodes().stream().anyMatch(s -> s==null || !s.matches("[A-Z0-9]{6}")) ||
            new HashSet<>(request.stockCodes()).size()!=request.stockCodes().size()) throw new IllegalArgumentException("Bounded KOSPI audit range and distinct stocks required");
        var snapshots=jdbc.queryForList("""
            SELECT DISTINCT ON (trading_date) id,trading_date,reported_row_count,
                count(*) OVER (PARTITION BY trading_date) AS versions
            FROM krx_price_snapshot WHERE endpoint='stk_bydd_trd' AND trading_date BETWEEN ? AND ?
            ORDER BY trading_date,collected_at DESC,id DESC
            """,request.from(),request.through());
        Map<LocalDate,Long> nonempty=new TreeMap<>();Set<LocalDate> stored=new HashSet<>();List<LocalDate> empty=new ArrayList<>();
        int multiple=0;long rows=0;
        for(var s:snapshots) {
            var day=((java.sql.Date)s.get("trading_date")).toLocalDate();stored.add(day);
            int count=((Number)s.get("reported_row_count")).intValue();rows+=count;
            if(count==0) empty.add(day);else nonempty.put(day,((Number)s.get("id")).longValue());
            if(((Number)s.get("versions")).intValue()>1) multiple++;
        }
        List<LocalDate> missing=request.from().datesUntil(request.through().plusDays(1)).filter(d -> !stored.contains(d)).toList();
        List<Company> companies=new ArrayList<>();
        for(String code:request.stockCodes()) {
            var quotes=jdbc.queryForList("""
                WITH latest AS (
                    SELECT DISTINCT ON (trading_date) id,trading_date,reported_row_count
                    FROM krx_price_snapshot WHERE endpoint='stk_bydd_trd' AND trading_date BETWEEN ? AND ?
                    ORDER BY trading_date,collected_at DESC,id DESC
                )
                SELECT s.trading_date,q.stock_code AS observed_code,q.open_price,q.high_price,q.low_price,q.close_price,q.volume,q.listed_shares
                FROM latest s LEFT JOIN krx_price_quote_staging q ON q.snapshot_id=s.id AND q.stock_code=?
                WHERE s.reported_row_count>0 ORDER BY s.trading_date
                """,request.from(),request.through(),code);
            List<LocalDate> absent=new ArrayList<>(),unavailable=new ArrayList<>();List<SharesChange> changes=new ArrayList<>();
            BigDecimal priorShares=null;LocalDate priorDay=null;int observed=0;
            for(var q:quotes) {
                var day=((java.sql.Date)q.get("trading_date")).toLocalDate();
                if(q.get("observed_code")==null) {absent.add(day);continue;}
                observed++;
                boolean bad=List.of("open_price","high_price","low_price","close_price","volume").stream().anyMatch(k -> q.get(k)==null || ((BigDecimal)q.get(k)).signum()<=0);
                if(!bad) {
                    var high=(BigDecimal)q.get("high_price");var low=(BigDecimal)q.get("low_price");
                    var open=(BigDecimal)q.get("open_price");var close=(BigDecimal)q.get("close_price");
                    bad=high.compareTo(low)<0 || high.compareTo(open.max(close))<0 || low.compareTo(open.min(close))>0;
                }
                if(bad) unavailable.add(day);
                var shares=(BigDecimal)q.get("listed_shares");
                if(shares!=null && priorShares!=null && shares.compareTo(priorShares)!=0) changes.add(new SharesChange(code,priorDay,priorShares,day,shares));
                priorShares=shares;priorDay=day;
            }
            companies.add(new Company(code,observed,List.copyOf(absent),List.copyOf(unavailable),List.copyOf(changes)));
        }
        return new Report(request.from(),request.through(),(int)ChronoUnit.DAYS.between(request.from(),request.through())+1,
            stored.size(),missing,List.copyOf(empty),nonempty.size(),multiple,rows,List.copyOf(companies),0,false,
            evidence.calendar(request.from(),request.through(),stored,nonempty.keySet()),
            evidence.events(request.from(),request.through(),companies),
            evidence.windows(nonempty.keySet()),
            evidence.market(request.from(),request.through(),Set.copyOf(request.stockCodes())),
            List.of("HISTORICAL_PROVIDER_PUBLICATION_TIME_UNVERIFIED",
                "TRADING_CALENDAR_AND_SESSION_EXCEPTIONS_UNVERIFIED","CORPORATE_ACTION_COVERAGE_UNVERIFIED"),
            List.of("Latest collected snapshots are selected for this current audit only, never used as historical availability",
                "Calendar date review is bounded to its source profile; it does not verify historical session times or approve model inputs",
                "Share count changes flag investigation; unchanged counts do not prove absence of splits/dividends/actions",
                "FAQ 19/26 confirm unadjusted KRW prices and share volume; this does not approve model inputs",
                "OHLC is regular-session pricing but volume includes off-hours trades (FAQ 25/27); nonzero volume is not proof of a regular-session execution",
                "Missing values remain null; zero OHLC is preserved as reported, never repaired or assumed to prove a trading halt"));
    }
}
