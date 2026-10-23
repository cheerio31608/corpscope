package io.corpscope.dataset;

import io.corpscope.financial.vintage.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Local, read-only preview over an explicit normalized price contract; no KRX schema guesses or model training. */
@Service
public class TemporalDatasetBuilder {
    public static final String POLICY=PriceReturnPolicy.ID;
    public record Session(LocalDate date,Instant openAt,Instant closeAt) {}
    public record Quote(LocalDate date,BigDecimal open,BigDecimal close,BigDecimal volume,Instant availableAt) {}
    public record Prices(String source,String stockCode,String currency,String basis,boolean calendarVerified,
        LocalDate actionsReviewedFrom,LocalDate actionsReviewedThrough,List<LocalDate> corporateActionDates,
        List<Session> sessions,List<Quote> quotes,List<PriceReturnPolicy.Action> corporateActions) {
        public Prices {
            corporateActions=corporateActions==null ? List.of() : List.copyOf(corporateActions);
        }
        public Prices(String source,String stockCode,String currency,String basis,boolean calendarVerified,
            LocalDate actionsReviewedFrom,LocalDate actionsReviewedThrough,List<LocalDate> corporateActionDates,
            List<Session> sessions,List<Quote> quotes) {
            this(source,stockCode,currency,basis,calendarVerified,actionsReviewedFrom,actionsReviewedThrough,
                corporateActionDates,sessions,quotes,List.of());
        }
    }
    public record Request(long companyId,List<LocalDate> decisionDates,Prices prices,TemporalSplitPolicy splitPolicy) {}
    public record Label(LocalDate entryDate,Instant entryAt,BigDecimal entryOpen,LocalDate exitDate,
        Instant exitAt,BigDecimal exitClose,Instant availableAt,BigDecimal priceChangePercent) {}
    public record Evidence(String financialReceipt,String financialHash,int fiscalYear,LocalDate financialAvailableFrom,
        String previousFinancialReceipt,LocalDate latestInputPriceDate,Instant latestInputPriceAvailableAt,
        List<String> financialWarnings,List<PriceReturnPolicy.CashDividendNote> cashDividendsOutsideModelInputs) {}
    public record Row(LocalDate decisionDate,Instant predictionAt,String split,Map<String,BigDecimal> modelInputs,
        Label label,Evidence evidence) {}
    public record Withheld(LocalDate decisionDate,String reason) {}
    public record Result(String policy,String source,int requested,int exported,int withheldCount,
        int independentFinancialPeriods,Map<String,Long> splitCounts,List<Row> rows,List<Withheld> withheld,
        boolean forecastReady,List<String> limitations) {}
    private final LatestAnnualFinancialService financials;
    public TemporalDatasetBuilder(LatestAnnualFinancialService financials) { this.financials=financials; }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result build(Request request) {
        validate(request);
        var company=financials.company(request.companyId());
        var p=request.prices();
        if(company.stockCode()==null || !company.stockCode().equals(p.stockCode()))
            throw new IllegalArgumentException("Price stock code does not match company");
        var sessions=p.sessions().stream().sorted(Comparator.comparing(Session::date)).toList();
        Map<LocalDate,Quote> quotes=new HashMap<>();p.quotes().forEach(q -> quotes.put(q.date(),q));
        List<Row> rows=new ArrayList<>();List<Withheld> withheld=new ArrayList<>();
        for(var date:request.decisionDates().stream().sorted().toList()) {
            Instant at=date.atStartOfDay(TemporalSplitPolicy.KST).toInstant();
            if(!"UNADJUSTED".equals(p.basis())) { withheld.add(new Withheld(date,"UNVERIFIED_PRICE_BASIS"));continue; }
            if(!p.calendarVerified()) { withheld.add(new Withheld(date,"UNVERIFIED_TRADING_CALENDAR"));continue; }
            var known=p.quotes().stream().filter(q -> !q.availableAt().isAfter(at))
                .sorted(Comparator.comparing(Quote::date)).toList();
            if(known.size()<2) { withheld.add(new Withheld(date,"INSUFFICIENT_PUBLISHED_PRICE_HISTORY"));continue; }
            var last=known.getLast();var prior=known.get(known.size()-2);
            int lastIndex=index(sessions,last.date()),priorIndex=index(sessions,prior.date());
            if(lastIndex!=priorIndex+1 || last.close().signum()<=0 || prior.close().signum()<=0) {
                withheld.add(new Withheld(date,"MISSING_OR_NONPOSITIVE_CONSECUTIVE_PRICE_INPUT"));continue;
            }
            int entry=0;while(entry<sessions.size() && !sessions.get(entry).openAt().isAfter(at)) entry++;
            int exit=entry+62; // Entry session is session 1; exit at the close of session 63.
            if(exit>=sessions.size()) { withheld.add(new Withheld(date,"INSUFFICIENT_63_SESSION_CALENDAR"));continue; }
            var start=sessions.get(entry);var end=sessions.get(exit);
            if(p.actionsReviewedFrom()==null || p.actionsReviewedThrough()==null ||
                p.actionsReviewedFrom().isAfter(prior.date()) || p.actionsReviewedThrough().isBefore(end.date())) {
                withheld.add(new Withheld(date,"UNVERIFIED_CORPORATE_ACTION_WINDOW"));continue;
            }
            if(PriceReturnPolicy.hasBlockingAction(p.corporateActionDates(),p.corporateActions(),prior.date(),end.date())) {
                withheld.add(new Withheld(date,"CORPORATE_ACTION_IN_RAW_PRICE_WINDOW"));continue;
            }
            boolean missing=false;
            Instant labelAvailable=end.closeAt();
            for(int i=entry;i<=exit;i++) {
                var q=quotes.get(sessions.get(i).date());
                if(q==null) { missing=true;break; }
                if(q.availableAt().isAfter(labelAvailable)) labelAvailable=q.availableAt();
            }
            if(missing) { withheld.add(new Withheld(date,"MISSING_HOLDING_WINDOW_PRICE"));continue; }
            var entryQuote=quotes.get(start.date());var exitQuote=quotes.get(end.date());
            if(entryQuote.open().signum()<=0 || exitQuote.open().signum()<=0 || exitQuote.close().signum()<=0 ||
                entryQuote.volume().signum()==0 || exitQuote.volume().signum()==0) {
                withheld.add(new Withheld(date,"UNEXECUTABLE_ENTRY_OR_EXIT"));continue;
            }
            String split=request.splitPolicy().split(date,end.closeAt(),labelAvailable);
            if(!Set.of("TRAIN","VALIDATION","TEST").contains(split)) { withheld.add(new Withheld(date,split));continue; }
            FinancialFeatureResponse f;
            try { f=financials.latest(company.id(),date); }
            catch(FinancialAsOfUnavailableException ex) { withheld.add(new Withheld(date,ex.reason().name()));continue; }
            if(!f.analysisAllowed()) { withheld.add(new Withheld(date,"ANALYSIS_EXCLUDED"));continue; }
            if(!f.unavailableMetricReasons().isEmpty()) { withheld.add(new Withheld(date,"INCOMPLETE_FINANCIAL_METRICS"));continue; }
            if(!f.corpCode().equals(company.corpCode()) || !f.asOf().equals(date) ||
                f.evidence().availableFromKstDate().isAfter(date) ||
                (f.comparisonEvidence()!=null && (f.comparisonEvidence().evidenceAvailableFromKstDate().isAfter(date) ||
                f.comparisonEvidence().previous().availableFromKstDate().isAfter(date))))
                throw new IllegalArgumentException("Financial evidence belongs to another company or future date");
            Map<String,BigDecimal> inputs=new LinkedHashMap<>();
            f.values().forEach((k,v) -> inputs.put("financial_"+k.name().toLowerCase(Locale.ROOT),v));
            inputs.put("revenueGrowth",f.metrics().revenueGrowth());inputs.put("operatingMargin",f.metrics().operatingMargin());
            inputs.put("debtRatio",f.metrics().debtRatio());inputs.put("roa",f.metrics().roa());inputs.put("roe",f.metrics().roe());
            if(f.comparisonEvidence()==null || inputs.size()!=11 || inputs.values().stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Complete reviewed financial pair required");
            inputs.put("price_last_close",last.close());inputs.put("price_return_1_session",change(last.close(),prior.close()));
            inputs.put("price_last_volume",last.volume());
            rows.add(new Row(date,at,split,Map.copyOf(inputs),
                new Label(start.date(),start.openAt(),entryQuote.open(),end.date(),end.closeAt(),exitQuote.close(),labelAvailable,
                    change(exitQuote.close(),entryQuote.open())),
                new Evidence(f.evidence().receiptNumber(),f.evidence().documentSha256(),f.fiscalYear(),f.evidence().availableFromKstDate(),
                    f.comparisonEvidence().previous().receiptNumber(),last.date(),last.availableAt(),f.warnings(),
                    PriceReturnPolicy.cashDividendNotes(p.corporateActions(),prior.date(),end.date(),at))));
        }
        var counts=new TreeMap<String,Long>();rows.forEach(r -> counts.merge(r.split(),1L,Long::sum));
        int periods=(int)rows.stream().map(r -> r.evidence().fiscalYear()).distinct().count();
        return new Result(POLICY,p.source(),request.decisionDates().size(),rows.size(),withheld.size(),periods,
            Map.copyOf(counts),List.copyOf(rows),List.copyOf(withheld),false,List.of(
                "Preview only; normalized price provenance, adjustment and calendar need independent official verification",
                "DEMO is synthetic and must never be represented as collected market data",
                "Raw KRW price change excludes dividends, fees, slippage and guarantees of execution",
                "Cash dividend notices are retrospective evidence outside numeric model inputs, never added to returns; unknown/noncash actions remain blocked",
                "Decision at KST 00:00, published past prices only; entry at next session open and exit at session 63 close",
                "Market and financial evidence identifiers, warnings and labels are outside numeric model inputs",
                "Repeated annual observations are not independent periods; overlapping 63-session labels remain dependent",
                "No baseline/model fitted, no performance or forecast-readiness claim"));
    }
    private static int index(List<Session> sessions,LocalDate date) {
        for(int i=0;i<sessions.size();i++) if(sessions.get(i).date().equals(date)) return i;
        throw new IllegalArgumentException("Quote outside supplied trading calendar");
    }
    private static BigDecimal change(BigDecimal end,BigDecimal start) {
        return PriceReturnPolicy.changePercent(start,end);
    }
    private static void validate(Request r) {
        if(r==null || r.companyId()<=0 || r.splitPolicy()==null || r.decisionDates()==null || r.decisionDates().isEmpty() ||
            r.decisionDates().size()>100 || r.decisionDates().stream().anyMatch(Objects::isNull) || new HashSet<>(r.decisionDates()).size()!=r.decisionDates().size())
            throw new IllegalArgumentException("Request requires 1..100 distinct decision dates");
        var p=r.prices();
        if(p==null || p.source()==null || !Set.of("DEMO","KRX").contains(p.source()) || p.stockCode()==null || !p.stockCode().matches("[0-9]{6}") ||
            !"KRW".equals(p.currency()) || p.basis()==null || !Set.of("UNADJUSTED","UNVERIFIED").contains(p.basis()) || p.sessions()==null || p.quotes()==null ||
            ("UNADJUSTED".equals(p.basis()) && p.calendarVerified() && p.sessions().isEmpty()) || p.sessions().size()>10000 || p.quotes().size()>10000 || p.corporateActionDates()==null ||
            p.corporateActionDates().stream().anyMatch(Objects::isNull) || (p.actionsReviewedFrom()!=null && p.actionsReviewedThrough()!=null &&
            p.actionsReviewedFrom().isAfter(p.actionsReviewedThrough()))) throw new IllegalArgumentException("Invalid explicit price contract");
        var byDate=new HashMap<LocalDate,Session>();
        for(var s:p.sessions()) {
            if(s==null || s.date()==null || s.openAt()==null || s.closeAt()==null || !s.openAt().isBefore(s.closeAt()) ||
                !s.openAt().atZone(TemporalSplitPolicy.KST).toLocalDate().equals(s.date()) ||
                !s.closeAt().atZone(TemporalSplitPolicy.KST).toLocalDate().equals(s.date()) || byDate.put(s.date(),s)!=null)
                throw new IllegalArgumentException("Invalid or duplicate trading session");
        }
        Set<LocalDate> days=new HashSet<>();
        for(var q:p.quotes()) {
            if(q==null || q.date()==null || !byDate.containsKey(q.date()) || !days.add(q.date()) || q.open()==null || q.close()==null ||
                q.volume()==null || q.availableAt()==null || q.open().signum()<0 || q.close().signum()<0 || q.volume().signum()<0 ||
                q.availableAt().isBefore(byDate.get(q.date()).closeAt())) throw new IllegalArgumentException("Invalid quote or publication timing");
        }
    }
}
