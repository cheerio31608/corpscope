package io.corpscope.dataset;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TemporalSplitPolicyTest {
    final TemporalSplitPolicy policy=new TemporalSplitPolicy(LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),Instant.parse("2026-01-01T00:00:00Z"));
    @Test void crossingTrainLabelIsPurgedAtExactValidationBoundary() {
        var boundary=LocalDate.of(2024,1,1).atStartOfDay(TemporalSplitPolicy.KST).toInstant();
        assertThat(policy.split(LocalDate.of(2023,12,1),boundary.minusSeconds(1),boundary.minusSeconds(1))).isEqualTo("TRAIN");
        assertThat(policy.split(LocalDate.of(2023,12,1),boundary.minusSeconds(1),boundary)).isEqualTo("PURGED_TRAIN");
    }
    @Test void crossingValidationLabelIsPurgedAndTestNeverBecomesTraining() {
        var boundary=LocalDate.of(2025,1,1).atStartOfDay(TemporalSplitPolicy.KST).toInstant();
        assertThat(policy.split(LocalDate.of(2024,12,1),boundary,boundary)).isEqualTo("PURGED_VALIDATION");
        assertThat(policy.split(LocalDate.of(2025,1,1),boundary.plusSeconds(86400),boundary.plusSeconds(86400))).isEqualTo("TEST");
    }
    @Test void labelsNotYetPublishedAtFrozenEvaluationCutoffAreUnavailable() {
        assertThat(policy.split(LocalDate.of(2025,12,1),Instant.parse("2025-12-31T00:00:00Z"),Instant.parse("2026-01-02T00:00:00Z")))
            .isEqualTo("LABEL_NOT_YET_AVAILABLE");
    }
}
