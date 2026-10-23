package io.corpscope.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KrxEvidenceReviewTest {
    final KrxEvidenceReview review = new KrxEvidenceReview(new ObjectMapper());
    LocalDate day(String value) { return LocalDate.parse(value); }

    @Test void electionHolidayAndWeekendAreClosedWithoutApprovingSessionTimes() {
        var start = day("2025-06-01"); var end = day("2025-06-06");
        var stored = new HashSet<>(start.datesUntil(end.plusDays(1)).toList());
        var result = review.calendar(start,end,stored,Set.of(day("2025-06-02"),day("2025-06-04"),day("2025-06-05")));
        assertThat(result.observedDatesMatch()).isTrue();
        assertThat(result.expectedNonemptyDays()).isEqualTo(3);
        assertThat(result.expectedClosedDays()).isEqualTo(3);
        assertThat(result.sessionTimesReviewed()).isFalse();
    }
    @Test void missingResponseIsDifferentFromUnexpectedEmptyTradingDay() {
        var result = review.calendar(day("2025-06-02"),day("2025-06-04"),Set.of(day("2025-06-02"),day("2025-06-03")),Set.of(day("2025-06-03")));
        assertThat(result.missingResponses()).containsExactly(day("2025-06-04"));
        assertThat(result.unexpectedEmptyDates()).containsExactly(day("2025-06-02"));
        assertThat(result.unexpectedNonemptyDates()).containsExactly(day("2025-06-03"));
        assertThat(result.observedDatesMatch()).isFalse();
    }
    @Test void unknownFutureRangeDoesNotInheritHolidayApproval() {
        var result = review.calendar(day("2025-12-31"),day("2026-01-01"),Set.of(),Set.of());
        assertThat(result.datePatternReviewed()).isFalse();
        assertThat(result.expectedNonemptyDays()).isNull();
        assertThat(result.observedDatesMatch()).isFalse();
    }
    KrxStagingAuditService.Company samsung(String before, String after, String changedOn) {
        var change = new KrxStagingAuditService.SharesChange("005930",day("2025-03-05"),new BigDecimal(before),day(changedOn),new BigDecimal(after));
        return new KrxStagingAuditService.Company("005930",2,List.of(),List.of(),List.of(change));
    }
    @Test void officialCancellationExplainsExactListingDateAndBothShareCounts() {
        var events = review.events(day("2025-03-01"),day("2025-07-31"),List.of(samsung("5969782550","5919637922","2025-03-06")));
        var event = events.getFirst();
        assertThat(event.matchesObservedSharesChange()).isTrue();
        assertThat(event.retiredOn()).isEqualTo(day("2025-02-20"));
        assertThat(event.listedOn()).isEqualTo(day("2025-03-06"));
        assertThat(event.receiptNumber()).isEqualTo("20250228000236");
    }
    @Test void changedAmountOrDateCannotReuseExactEventExplanation() {
        for (var company : List.of(samsung("5969782550","5919637921","2025-03-06"),samsung("5969782550","5919637922","2025-03-07")))
            assertThat(review.events(day("2025-03-01"),day("2025-07-31"),List.of(company)).getFirst().matchesObservedSharesChange()).isFalse();
    }
    @Test void unrelatedCompanyAndOutsideEventRangeHaveNoImpliedActionEvidence() {
        assertThat(review.events(day("2025-04-01"),day("2025-04-30"),List.of(samsung("5969782550","5919637922","2025-03-06")))).isEmpty();
        assertThat(review.events(day("2025-03-01"),day("2025-07-31"),List.of(new KrxStagingAuditService.Company("000660",2,List.of(),List.of(),List.of())))).isEmpty();
    }
    @Test void countsEntryInclusive63DatesAfterTwoPriorDatesWithoutApprovingAnyLabels() {
        // Artificial continuous dates for index arithmetic only, not a trading calendar.
        var dates = new HashSet<>(day("2025-01-01").datesUntil(day("2025-01-01").plusDays(103)).toList());
        var result = review.windows(dates);
        assertThat(result.structurallyCoveredDates()).isEqualTo(39);
        assertThat(result.missingTwoPriorDates()).isEqualTo(2);
        assertThat(result.missing63DateForwardWindow()).isEqualTo(62);
        assertThat(result.candidateEntryDates().getFirst()).isEqualTo(day("2025-01-03"));
        assertThat(result.candidateEntryDates().getLast()).isEqualTo(day("2025-02-10"));
        assertThat(result.approvedForLabels()).isFalse();
    }
    @Test void shortAndEmptyRangesCannotProduceStructuralCandidates() {
        for (int size : List.of(0,1,2,63,64)) {
            var dates = new HashSet<>(day("2025-01-01").datesUntil(day("2025-01-01").plusDays(size)).toList());
            var result = review.windows(dates);
            assertThat(result.structurallyCoveredDates()).isZero();
            assertThat(result.missingTwoPriorDates() + result.missing63DateForwardWindow()).isEqualTo(size);
        }
    }
    @Test void naverCancellationRequiresExactCompanyDateAndBothCounts() {
        for (String code : List.of("035420", "005930")) {
            for (String after : List.of("156852638", "156852637")) {
                for (String date : List.of("2025-08-29", "2025-08-12")) {
                    var change = new KrxStagingAuditService.SharesChange(code, day("2025-08-28"),
                        new BigDecimal("158437008"), day(date), new BigDecimal(after));
                    var company = new KrxStagingAuditService.Company(code,2,List.of(),List.of(),List.of(change));
                    var events = review.events(day("2025-08-01"),day("2025-08-31"),List.of(company));
                    if (code.equals("005930")) { assertThat(events).isEmpty(); continue; }
                    var event = events.getFirst();
                    assertThat(event.matchesObservedSharesChange()).isEqualTo(after.equals("156852638") && date.equals("2025-08-29"));
                    assertThat(event.retiredOn()).isEqualTo(day("2025-08-12"));
                    assertThat(event.receiptNumber()).isEqualTo("20250826000123");
                }
            }
        }
        assertThat(review.events(day("2025-09-01"),day("2025-09-30"),
            List.of(new KrxStagingAuditService.Company("035420",0,List.of(),List.of(),List.of())))).isEmpty();
    }
    @Test void reviewedYearBoundaryClosuresAndWeekendsDoNotApproveSessionTimes() {
        var start = day("2025-12-25"); var end = day("2025-12-31");
        var stored = new HashSet<>(start.datesUntil(end.plusDays(1)).toList());
        var result = review.calendar(start,end,stored,Set.of(day("2025-12-26"),day("2025-12-29"),day("2025-12-30")));
        assertThat(result.datePatternReviewed()).isTrue();
        assertThat(result.observedDatesMatch()).isTrue();
        assertThat(result.expectedClosedDays()).isEqualTo(4);
        assertThat(result.sessionTimesReviewed()).isFalse();
    }
    @Test void partialNoticesDistinguishNewYearCloseFromExamDayCloseWithoutDefaultingOtherDates() {
        var year = review.market(day("2025-01-01"),day("2025-12-31"),Set.of("005930"));
        assertThat(year.sessionNotices()).extracting(KrxEvidenceReview.SessionNotice::tradingDate)
            .containsExactly(day("2025-01-02"),day("2025-11-13"));
        assertThat(year.sessionNotices()).extracting(KrxEvidenceReview.SessionNotice::regularClose)
            .containsExactly(java.time.LocalTime.of(15,30),java.time.LocalTime.of(16,30));
        assertThat(year.sessionNotices()).allSatisfy(n -> {
            assertThat(n.regularOpen()).isEqualTo(java.time.LocalTime.of(10,0));
            assertThat(n.zone()).isEqualTo("Asia/Seoul");
        });
        assertThat(review.market(day("2025-01-03"),day("2025-01-03"),Set.of("005930")).sessionNotices()).isEmpty();
        assertThat(year.fullSessionCoverageReviewed()).isFalse();
        assertThat(year.historicalPublicationTimesReviewed()).isFalse();
        assertThat(year.approvedForDataset()).isFalse();
    }
    @Test void dividendNoticesAreScopedByExactStockAndEffectiveDateAndNeverClearCoverage() {
        var result = review.market(day("2025-08-28"),day("2025-08-28"),Set.of("000660"));
        assertThat(result.cashDividendNotices()).singleElement().satisfies(n -> {
            assertThat(n.receiptNumber()).isEqualTo("20250827000340");
            assertThat(n.announcedAt()).isEqualTo(java.time.OffsetDateTime.parse("2025-08-27T15:57:00+09:00"));
        });
        assertThat(review.market(day("2025-08-27"),day("2025-08-27"),Set.of("000660")).cashDividendNotices()).isEmpty();
        assertThat(review.market(day("2025-08-28"),day("2025-08-28"),Set.of("005930")).cashDividendNotices()).isEmpty();
        assertThat(result.fullCorporateActionCoverageReviewed()).isFalse();
        assertThat(result.historicalPublicationTimesReviewed()).isFalse();
        assertThat(result.approvedForDataset()).isFalse();
    }
}
