package io.corpscope.dataset;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static io.corpscope.dataset.PriceReturnPolicy.*;

class PriceReturnPolicyTest {
    final LocalDate from=LocalDate.parse("2025-03-01"), through=LocalDate.parse("2025-07-31");
    Action action(ActionType type, LocalDate date) {
        return new Action(type,date,Instant.parse("2025-05-28T07:10:00Z"),"20250528000380","a".repeat(64));
    }
    @Test void calculatesPriceOnlyPercentWithNoDividendAddition() {
        assertThat(changePercent(new BigDecimal("100"),new BigDecimal("90"))).isEqualByComparingTo("-10.0000");
        assertThat(changePercent(new BigDecimal("3"),new BigDecimal("4"))).isEqualByComparingTo("33.3333");
    }
    @Test void invalidPricesNeverBecomeZeroReturns() {
        for(var value:Arrays.asList(null,BigDecimal.ZERO,BigDecimal.ONE.negate()))
            assertThatThrownBy(()->changePercent(value,BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cashDividendIsAllowedButEveryNoncashOrUnknownTypeBlocksWithoutAdjustmentEvidence() {
        for(var type:ActionType.values())
            assertThat(hasBlockingAction(List.of(),List.of(action(type,from)),from,through)).isEqualTo(type!=ActionType.CASH_DIVIDEND);
    }
    @Test void legacyUnclassifiedDatesRemainBlockingAndWindowBoundariesAreInclusive() {
        assertThat(hasBlockingAction(List.of(through),List.of(),from,through)).isTrue();
        assertThat(hasBlockingAction(List.of(from.minusDays(1),through.plusDays(1)),List.of(),from,through)).isFalse();
    }
    @Test void futureAnnouncementOnlyAppearsAsRetrospectiveNoteAndDoesNotChangeReturn() {
        var dividend=action(ActionType.CASH_DIVIDEND,LocalDate.parse("2025-05-29"));
        var note=cashDividendNotes(List.of(dividend),from,through,Instant.parse("2025-03-01T00:00:00Z")).getFirst();
        assertThat(note.announcementKnownAtDecision()).isFalse();
        assertThat(note.exDividendDate()).isEqualTo(dividend.effectiveOn());
        assertThat(cashDividendNotes(List.of(dividend),from,through,dividend.announcedAt()).getFirst().announcementKnownAtDecision()).isTrue();
        assertThat(CashDividendNote.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName).doesNotContain("amount","dividendAmount");
    }
    @Test void incompleteSourceEvidenceIsRejected() {
        assertThatThrownBy(()->new Action(ActionType.CASH_DIVIDEND,from,Instant.EPOCH,"bad","a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
}
