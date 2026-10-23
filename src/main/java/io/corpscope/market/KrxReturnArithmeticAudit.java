package io.corpscope.market;

import io.corpscope.dataset.*;
import io.corpscope.financial.vintage.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Retrospective arithmetic on observed response dates, NOT approved sessions/labels or training features. */
@Service
public class KrxReturnArithmeticAudit {
    public record Request(long companyId, LocalDate from, LocalDate through) {}
    public record Row(LocalDate observedEntryDate, LocalDate observedExitDate, BigDecimal entryOpen,
        BigDecimal exitClose, BigDecimal observedPriceChangePercent, String entrySourceHash, String exitSourceHash,
        Instant entryCollectedAt, Instant exitCollectedAt, Integer selectedFiscalYear, String financialReceipt,
        String financialStatus, boolean approvedForDataset) {}
    public record Held(LocalDate observedEntryDate, String reason) {}
    public record Result(String policy, long companyId, String stockCode, int observedDates, int calculated,
        List<Row> rows, List<Held> withheld, boolean forecastReady, List<String> limitations) {}
    private final JdbcTemplate jdbc;
    private final LatestAnnualFinancialService financials;
    public KrxReturnArithmeticAudit(JdbcTemplate jdbc, LatestAnnualFinancialService financials) {
        this.jdbc=jdbc; this.financials=financials;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public Result review(Request request) {
        if (request==null || request.companyId()<=0 || request.from()==null || request.through()==null ||
            request.through().isBefore(request.from()) || ChronoUnit.DAYS.between(request.from(),request.through())>=366)
            throw new IllegalArgumentException("Bounded company/date request required");
        var company=financials.company(request.companyId());
        var data=jdbc.queryForList("""
            WITH latest AS (
                SELECT DISTINCT ON (trading_date) id,trading_date,payload_sha256,collected_at,reported_row_count
                FROM krx_price_snapshot WHERE endpoint='stk_bydd_trd' AND trading_date BETWEEN ? AND ?
                ORDER BY trading_date,collected_at DESC,id DESC
            )
            SELECT s.trading_date,s.payload_sha256,s.collected_at,q.open_price,q.close_price,q.volume
            FROM latest s LEFT JOIN krx_price_quote_staging q ON q.snapshot_id=s.id AND q.stock_code=?
            WHERE s.reported_row_count>0 ORDER BY s.trading_date
            """,request.from(),request.through(),company.stockCode());
        List<Row> rows=new ArrayList<>(); List<Held> held=new ArrayList<>();
        for(int entry=0;entry<data.size();entry++) {
            var start=data.get(entry); var date=date(start);
            if(entry<2) {held.add(new Held(date,"TWO_PRIOR_OBSERVED_DATES_NOT_AVAILABLE"));continue;}
            int exit=entry+62;
            if(exit>=data.size()) {held.add(new Held(date,"FORWARD_63_OBSERVED_DATES_NOT_AVAILABLE"));continue;}
            boolean invalid=false;
            for(int i=entry-2;i<=exit;i++) {
                var q=data.get(i);
                if(List.of("open_price","close_price","volume").stream().anyMatch(k -> q.get(k)==null || ((BigDecimal)q.get(k)).signum()<=0)) {
                    invalid=true;break;
                }
            }
            if(invalid) {held.add(new Held(date,"MISSING_OR_NONPOSITIVE_OBSERVED_QUOTE"));continue;}
            var end=data.get(exit); Integer year=null; String receipt=null,status;
            try {
                var f=financials.latest(company.id(),date); year=f.fiscalYear(); receipt=f.evidence().receiptNumber();
                status=!f.analysisAllowed() ? "ANALYSIS_EXCLUDED" : f.unavailableMetricReasons().isEmpty() ? "REVIEWED_FINANCIALS_ONLY" : "PARTIAL_FINANCIALS";
            } catch(FinancialAsOfUnavailableException ex) {status=ex.reason().name();}
            var open=(BigDecimal)start.get("open_price"); var close=(BigDecimal)end.get("close_price");
            rows.add(new Row(date,date(end),open,close,PriceReturnPolicy.changePercent(open,close),
                (String)start.get("payload_sha256"),(String)end.get("payload_sha256"),
                ((java.sql.Timestamp)start.get("collected_at")).toInstant(),((java.sql.Timestamp)end.get("collected_at")).toInstant(),
                year,receipt,status,false));
        }
        return new Result(PriceReturnPolicy.ID,company.id(),company.stockCode(),data.size(),rows.size(),List.copyOf(rows),List.copyOf(held),false,
            List.of("Retrospective arithmetic on 63 observed response dates, not a verified 63-session label",
                "Calendar completeness/session times, corporate action coverage and historical provider publication remain unverified",
                "Latest collected snapshots are used for current audit only; collection times are never backdated",
                "Financial selection uses the entry date independently; arithmetic success does not authorize price/financial dataset export",
                "Cash dividends, fees and slippage excluded; no adjustment factors or model inputs generated"));
    }
    private static LocalDate date(Map<String,Object> row) {return ((java.sql.Date)row.get("trading_date")).toLocalDate();}
}
