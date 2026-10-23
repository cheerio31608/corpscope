package io.corpscope.dataset;

import java.math.*;
import java.time.*;
import java.util.*;

/** Price-only return: cash dividends are annotations; unadjusted noncash/unknown actions block the window. */
public final class PriceReturnPolicy {
    public static final String ID = "PILOT_63_SESSIONS_V2_PRICE_ONLY_ENTRY_OPEN_EXIT_CLOSE";
    public enum ActionType { CASH_DIVIDEND, STOCK_SPLIT, STOCK_MERGER, RIGHTS_ISSUE, SHARE_CANCELLATION, UNKNOWN }
    public record Action(ActionType type, LocalDate effectiveOn, Instant announcedAt, String receiptNumber, String sourceSha256) {
        public Action {
            if (type == null || effectiveOn == null || announcedAt == null || receiptNumber == null ||
                !receiptNumber.matches("[0-9]{14}") || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Explicit corporate action date and source evidence required");
        }
    }
    public record CashDividendNote(LocalDate exDividendDate, String receiptNumber, Instant announcedAt,
        boolean announcementKnownAtDecision) {}
    private PriceReturnPolicy() {}

    public static BigDecimal changePercent(BigDecimal start, BigDecimal end) {
        if (start == null || end == null || start.signum() <= 0 || end.signum() <= 0)
            throw new IllegalArgumentException("Positive entry and exit prices required");
        return end.subtract(start).multiply(BigDecimal.valueOf(100)).divide(start, 4, RoundingMode.HALF_UP);
    }

    public static boolean hasBlockingAction(List<LocalDate> legacyDates, List<Action> actions, LocalDate from, LocalDate through) {
        return legacyDates.stream().anyMatch(d -> within(d, from, through)) ||
            actions.stream().anyMatch(a -> within(a.effectiveOn(), from, through) && a.type() != ActionType.CASH_DIVIDEND);
    }

    public static List<CashDividendNote> cashDividendNotes(List<Action> actions, LocalDate from, LocalDate through, Instant decision) {
        return actions.stream().filter(a -> a.type() == ActionType.CASH_DIVIDEND && within(a.effectiveOn(), from, through))
            .sorted(Comparator.comparing(Action::effectiveOn).thenComparing(Action::receiptNumber))
            .map(a -> new CashDividendNote(a.effectiveOn(), a.receiptNumber(), a.announcedAt(), !a.announcedAt().isAfter(decision))).toList();
    }
    private static boolean within(LocalDate day, LocalDate from, LocalDate through) {
        return !day.isBefore(from) && !day.isAfter(through);
    }
}
