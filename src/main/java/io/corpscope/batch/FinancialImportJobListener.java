package io.corpscope.batch;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

@Component
public class FinancialImportJobListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(FinancialImportJobListener.class);
    private final JobRepository repository;

    public FinancialImportJobListener(JobRepository repository) { this.repository = repository; }

    @Override
    public void afterJob(JobExecution execution) {
        BatchFailure failure = execution.getAllFailureExceptions().isEmpty()
                ? new BatchFailure("NONE", "No failure")
                : BatchFailure.from(execution.getAllFailureExceptions().getFirst());
        execution.getExecutionContext().putString("failureCategory", failure.category());
        execution.getExecutionContext().putString("failureSummary", failure.summary());
        repository.updateExecutionContext(execution);
        long written = execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
        long rollbacks = execution.getStepExecutions().stream().mapToLong(StepExecution::getRollbackCount).sum();
        Long elapsed = execution.getStartTime() == null || execution.getEndTime() == null ? null
                : Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis();
        log.info("Financial import execution={} status={} companiesWritten={} rollbacks={} elapsedMs={} failureCategory={}",
                execution.getId(), execution.getStatus(), written, rollbacks, elapsed, failure.category());
    }
}
