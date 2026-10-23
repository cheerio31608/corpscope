package io.corpscope.dataset;

import io.corpscope.analysis.FinancialMetricsCalculator;
import io.corpscope.financial.*;
import io.corpscope.financial.vintage.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.corpscope.dataset.TemporalDatasetBuilder.*;

/** Artificial trading days/quotes only; never inserted into the real company database. */
class TemporalDatasetBuilderTest {
    LatestAnnualFinancialService financials;
    TemporalDatasetBuilder builder;
    List<Session> sessions;
    List<Quote> quotes;
    LocalDate day;
    @BeforeEach void setup() {
        financials=mock(LatestAnnualFinancialService.class);builder=new TemporalDatasetBuilder(financials);
        when(financials.company(1)).thenReturn(new LatestAnnualFinancialService.Company(1,"00126380","005930"));
        sessions=new ArrayList<>();quotes=new ArrayList<>();
        LocalDate date=LocalDate.of(2023,1,2);
        // Deliberately synthetic weekday calendar, NOT the actual KRX calendar.
        while(sessions.size()<120) {
            if(date.getDayOfWeek().getValue()<=5) {
                int i=sessions.size();
                var session=new Session(date,at(date,9),at(date,15).plusSeconds(1800));sessions.add(session);
                quotes.add(new Quote(date,BigDecimal.valueOf(100+i),BigDecimal.valueOf(101+i),BigDecimal.valueOf(1000),session.closeAt().plusSeconds(21600)));
            }
            date=date.plusDays(1);
        }
        day=sessions.get(10).date();
        when(financials.latest(eq(1L),any())).thenAnswer(inv -> feature(inv.getArgument(1),Map.of()));
    }
    static Instant at(LocalDate day,int hour) {return day.atTime(hour,0).atZone(TemporalSplitPolicy.KST).toInstant();}
    static FinancialFeatureResponse feature(LocalDate day,Map<String,String> missing) {
        var evidence=new FinancialFeatureResponse.Evidence("20220304000687",LocalDate.of(2022,3,4),LocalDate.of(2022,3,5),"a".repeat(64),"TEST","OPENDART_DOCUMENT","KRW");
        Map<StandardAccount,BigDecimal> amounts=new EnumMap<>(StandardAccount.class);
        for(var account:List.of(StandardAccount.REVENUE,StandardAccount.OPERATING_PROFIT,StandardAccount.NET_INCOME,
            StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY)) amounts.put(account,BigDecimal.TEN);
        var prior=new FinancialFeatureResponse.ComparisonEvidence("TEST",LocalDate.of(2022,3,5),2020,evidence,amounts);
        return new FinancialFeatureResponse("FINANCIAL_AS_OF_V2",1,"00126380",2021,ReportType.ANNUAL,StatementType.CFS,day,
            evidence,amounts,prior,new FinancialMetricsCalculator.Metrics(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE),
            true,null,missing,false,List.of());
    }
    Prices prices(List<Quote> data,boolean calendar,LocalDate from,LocalDate through,List<LocalDate> actions) {
        return new Prices("DEMO","005930","KRW","UNADJUSTED",calendar,from,through,actions,sessions,data);
    }
    Prices prices(List<Quote> data) {return prices(data,true,sessions.getFirst().date(),sessions.getLast().date(),List.of());}
    TemporalSplitPolicy policy() {return new TemporalSplitPolicy(LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),Instant.parse("2026-01-01T00:00:00Z"));}
    Result build(Prices prices) {return builder.build(new Request(1,List.of(day),prices,policy()));}
    @Test void alignsKstDecisionPastPricesNextOpenAndExactly63Sessions() {
        var result=build(prices(quotes));assertThat(result.exported()).isEqualTo(1);
        var row=result.rows().getFirst();
        assertThat(row.predictionAt()).isEqualTo(at(day,0));
        assertThat(row.label().entryDate()).isEqualTo(sessions.get(10).date());
        assertThat(row.label().exitDate()).isEqualTo(sessions.get(72).date());
        assertThat(row.label().priceChangePercent()).isEqualByComparingTo("57.2727");
        assertThat(row.modelInputs().get("price_last_close")).isEqualByComparingTo("110");
        assertThat(row.evidence().latestInputPriceDate()).isEqualTo(sessions.get(9).date());
        assertThat(row.evidence().latestInputPriceAvailableAt()).isBefore(row.predictionAt());
        assertThat(row.modelInputs()).hasSize(14).doesNotContainKeys("fiscalYear","receiptNumber","label","predictionAt","forecastReady");
        assertThat(result.source()).isEqualTo("DEMO");assertThat(result.forecastReady()).isFalse();
    }
    @Test void futurePriceMutationChangesLabelButCannotChangeAnyInputOrFinancialEvidence() {
        var before=build(prices(quotes)).rows().getFirst();
        var changed=quotes.stream().map(q -> q.date().isBefore(day) ? q : new Quote(q.date(),q.open(),q.close().multiply(BigDecimal.TEN),q.volume(),q.availableAt())).toList();
        var after=build(prices(changed)).rows().getFirst();
        assertThat(after.modelInputs()).isEqualTo(before.modelInputs());assertThat(after.evidence()).isEqualTo(before.evidence());
        assertThat(after.label().priceChangePercent()).isNotEqualByComparingTo(before.label().priceChangePercent());
    }
    @Test void delayedPricePublicationUsesOnlyActuallyAvailablePreviousSession() {
        var delayed=new ArrayList<>(quotes);var q=delayed.get(9);
        delayed.set(9,new Quote(q.date(),q.open(),q.close(),q.volume(),at(day.plusDays(1),13)));
        var row=build(prices(delayed)).rows().getFirst();
        assertThat(row.evidence().latestInputPriceDate()).isEqualTo(sessions.get(8).date());
        assertThat(row.modelInputs().get("price_last_close")).isEqualByComparingTo("109");
    }
    @ParameterizedTest @ValueSource(strings={"calendar","actions-unreviewed","action-in-window","missing-history","missing-label-price","halted-entry","indicative-exit","label-unavailable"})
    void withholdsUnsafeRowsAndAccountsForEveryRequest(String kind) {
        var data=new ArrayList<>(quotes);Prices panel;
        String expected;
        switch(kind) {
            case "calendar" -> {panel=prices(data,false,sessions.getFirst().date(),sessions.getLast().date(),List.of());expected="UNVERIFIED_TRADING_CALENDAR";}
            case "actions-unreviewed" -> {panel=prices(data,true,null,null,List.of());expected="UNVERIFIED_CORPORATE_ACTION_WINDOW";}
            case "action-in-window" -> {panel=prices(data,true,sessions.getFirst().date(),sessions.getLast().date(),List.of(sessions.get(40).date()));expected="CORPORATE_ACTION_IN_RAW_PRICE_WINDOW";}
            case "missing-history" -> {data.remove(8);panel=prices(data);expected="MISSING_OR_NONPOSITIVE_CONSECUTIVE_PRICE_INPUT";}
            case "missing-label-price" -> {data.remove(30);panel=prices(data);expected="MISSING_HOLDING_WINDOW_PRICE";}
            case "halted-entry" -> {var q=data.get(10);data.set(10,new Quote(q.date(),q.open(),q.close(),BigDecimal.ZERO,q.availableAt()));panel=prices(data);expected="UNEXECUTABLE_ENTRY_OR_EXIT";}
            case "indicative-exit" -> {var q=data.get(72);data.set(72,new Quote(q.date(),BigDecimal.ZERO,q.close(),q.volume(),q.availableAt()));panel=prices(data);expected="UNEXECUTABLE_ENTRY_OR_EXIT";}
            default -> {var q=data.get(40);data.set(40,new Quote(q.date(),q.open(),q.close(),q.volume(),Instant.parse("2027-01-01T00:00:00Z")));panel=prices(data);expected="LABEL_NOT_YET_AVAILABLE";}
        }
        var result=build(panel);assertThat(result.exported()).isZero();assertThat(result.withheldCount()).isEqualTo(result.requested());
        assertThat(result.withheld()).extracting(Withheld::reason).containsExactly(expected);
    }
    @Test void verifiedRawBasisWithoutVerifiedCalendarDoesNotRequireInventedSessions() {
        var prices=new Prices("KRX","005930","KRW","UNADJUSTED",false,null,null,List.of(),List.of(),List.of());
        assertThat(build(prices).withheld()).extracting(Withheld::reason).containsExactly("UNVERIFIED_TRADING_CALENDAR");
        verify(financials,never()).latest(anyLong(),any());
    }
    @Test void typedCashDividendChangesOnlyEvidenceWhileUnknownActionsStillBlock() {
        var original=build(prices(quotes)).rows().getFirst();
        var p=prices(quotes);
        var dividend=new PriceReturnPolicy.Action(PriceReturnPolicy.ActionType.CASH_DIVIDEND,sessions.get(40).date(),
            at(sessions.get(50).date(),12),"20250528000380","a".repeat(64));
        var typed=new Prices(p.source(),p.stockCode(),p.currency(),p.basis(),p.calendarVerified(),p.actionsReviewedFrom(),
            p.actionsReviewedThrough(),p.corporateActionDates(),p.sessions(),p.quotes(),List.of(dividend));
        var row=build(typed).rows().getFirst();
        assertThat(row.label()).isEqualTo(original.label());
        assertThat(row.modelInputs()).isEqualTo(original.modelInputs());
        assertThat(row.evidence().cashDividendsOutsideModelInputs().getFirst().announcementKnownAtDecision()).isFalse();
        var split=new PriceReturnPolicy.Action(PriceReturnPolicy.ActionType.STOCK_SPLIT,dividend.effectiveOn(),dividend.announcedAt(),dividend.receiptNumber(),dividend.sourceSha256());
        var unsafe=new Prices(p.source(),p.stockCode(),p.currency(),p.basis(),true,p.actionsReviewedFrom(),p.actionsReviewedThrough(),
            List.of(),p.sessions(),p.quotes(),List.of(split));
        assertThat(build(unsafe).withheld()).extracting(Withheld::reason).containsExactly("CORPORATE_ACTION_IN_RAW_PRICE_WINDOW");
    }
    @Test void missingFinancialMetricsRemainWithheldInsteadOfZeroFilled() {
        when(financials.latest(1,day)).thenReturn(feature(day,Map.of("roa","PREVIOUS_PERIOD_BASIS_NOT_VERIFIED")));
        assertThat(build(prices(quotes)).withheld()).extracting(Withheld::reason).containsExactly("INCOMPLETE_FINANCIAL_METRICS");
    }
    @Test void databaseFailuresPropagateInsteadOfAppearingAsMissingSamples() {
        when(financials.latest(1,day)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic failure"));
        assertThatThrownBy(() -> build(prices(quotes))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void annualObservationRepeatedOnDatesDoesNotBecomeMultipleIndependentFinancialPeriods() {
        var result=builder.build(new Request(1,List.of(day,day.plusDays(1)),prices(quotes),policy()));
        assertThat(result.exported()).isEqualTo(2);assertThat(result.independentFinancialPeriods()).isEqualTo(1);
    }
    @Test void futureFinancialEvidenceIsRejected() {
        when(financials.latest(1,day)).thenReturn(feature(day.plusDays(1),Map.of()));
        assertThatThrownBy(() -> build(prices(quotes))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void quoteCannotBePublishedBeforeItsSessionClose() {
        var data=new ArrayList<>(quotes);var q=data.get(0);data.set(0,new Quote(q.date(),q.open(),q.close(),q.volume(),sessions.get(0).openAt()));
        assertThatThrownBy(() -> build(prices(data))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unverifiedRealPriceContractIsExplicitlyWithheldWithoutInventingCalendarOrTimes() {
        var panel=new Prices("KRX","005930","KRW","UNVERIFIED",false,null,null,List.of(),List.of(),List.of());
        var result=build(panel);
        assertThat(result.source()).isEqualTo("KRX");assertThat(result.exported()).isZero();
        assertThat(result.withheld()).extracting(Withheld::reason).containsExactly("UNVERIFIED_PRICE_BASIS");
        verify(financials,never()).latest(anyLong(),any());
    }
}
