package io.corpscope.batch;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "corpscope.batch.schedule-enabled", havingValue = "true")
public class FinancialImportScheduler {
    private static final Logger log = LoggerFactory.getLogger(FinancialImportScheduler.class);
    private final JobLauncher launcher;
    private final Job job;
    private final String codes;
    private final long year;
    private final String report;
    private final String statement;

    public FinancialImportScheduler(JobLauncher launcher, Job financialImportJob,
            @Value("${corpscope.batch.corp-codes}") String codes,
            @Value("${corpscope.batch.fiscal-year}") long year,
            @Value("${corpscope.batch.report-type}") String report,
            @Value("${corpscope.batch.statement-type}") String statement) {
        this.launcher = launcher;
        this.job = financialImportJob;
        this.codes = codes;
        this.year = year;
        this.report = report;
        this.statement = statement;
    }

    @Scheduled(cron = "${corpscope.batch.cron}", zone = "Asia/Seoul")
    public void run() {
        var parameters = new JobParametersBuilder()
                .addString("corpCodes", codes).addLong("fiscalYear", year)
                .addString("reportType", report).addString("statementType", statement)
                .addString("requestId", LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString())
                .toJobParameters();
        try {
            JobExecution execution = launcher.run(job, parameters);
            log.info("Scheduled financial import execution {}: {}", execution.getId(), execution.getStatus());
        } catch (JobInstanceAlreadyCompleteException ex) {
            log.info("Today's scheduled import is already complete");
        } catch (Exception ex) {
            log.error("Scheduled financial import failed ({})", ex.getClass().getSimpleName());
        }
    }
}
