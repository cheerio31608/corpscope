package io.corpscope.operations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record BatchExecutionReport(Long executionId, Long instanceId, String jobName, String status,
        LocalDateTime startedAt, LocalDateTime endedAt, Long elapsedMs, Map<String, String> parameters,
        String failureCategory, String failureSummary, boolean restartable, String restartCommand,
        List<StepReport> steps) {
    public record StepReport(String name, String status, long companiesRead, long companiesWritten,
            long filtered, long skipped, long commits, long rollbacks, int committedTargetIndex,
            String failedCorpCode, String dataSource) {}
}
