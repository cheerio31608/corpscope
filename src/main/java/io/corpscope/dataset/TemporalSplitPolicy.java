package io.corpscope.dataset;

import java.time.*;

/** All companies share the same boundaries. Labels must be observable strictly before the next split. */
public record TemporalSplitPolicy(LocalDate validationStart,LocalDate testStart,Instant labelsKnownBy) {
    public static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public TemporalSplitPolicy {
        if(validationStart==null || testStart==null || labelsKnownBy==null || !validationStart.isBefore(testStart) ||
            !testStart.atStartOfDay(KST).toInstant().isBefore(labelsKnownBy))
            throw new IllegalArgumentException("Ordered temporal boundaries required");
    }
    public String split(LocalDate decision,Instant labelExit,Instant labelAvailable) {
        if(labelExit==null || labelAvailable==null || labelAvailable.isBefore(labelExit))
            throw new IllegalArgumentException("Invalid label timing");
        if(labelAvailable.isAfter(labelsKnownBy)) return "LABEL_NOT_YET_AVAILABLE";
        if(decision.isBefore(validationStart)) return before(labelExit,labelAvailable,validationStart) ? "TRAIN":"PURGED_TRAIN";
        if(decision.isBefore(testStart)) return before(labelExit,labelAvailable,testStart) ? "VALIDATION":"PURGED_VALIDATION";
        return "TEST";
    }
    private static boolean before(Instant end,Instant available,LocalDate boundary) {
        Instant cutoff=boundary.atStartOfDay(KST).toInstant();
        return end.isBefore(cutoff) && available.isBefore(cutoff);
    }
}
