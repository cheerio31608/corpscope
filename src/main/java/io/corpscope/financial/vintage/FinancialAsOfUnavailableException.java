package io.corpscope.financial.vintage;

import io.corpscope.common.NotFoundException;

/** Stable reasons shared by HTTP, coverage review and local exports; never parse exception prose. */
public class FinancialAsOfUnavailableException extends NotFoundException {
    public enum Reason {
        NO_FILING_COVERAGE, OUTSIDE_FILING_COVERAGE, NO_PUBLIC_FILING,
        AMBIGUOUS_PUBLICATION_DAY, UNREVIEWED_SELECTED_FILING, INCOMPLETE_REVIEWED_VALUES,
        COMPARISON_PAIR_NOT_VERIFIED
    }
    private final Reason reason;
    public FinancialAsOfUnavailableException(Reason reason,String message) {
        super(message);
        this.reason=reason;
    }
    public Reason reason() { return reason; }
}
