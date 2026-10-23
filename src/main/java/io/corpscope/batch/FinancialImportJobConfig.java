package io.corpscope.batch;

import io.corpscope.external.dart.DartException;
import io.corpscope.financial.*;
import io.corpscope.ingestion.*;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class FinancialImportJobConfig {
    @Bean
    Job financialImportJob(JobRepository repository, Step financialImportStep, FinancialImportJobListener listener) {
        // No RunIdIncrementer: identical identifying parameters restart a failed instance.
        return new JobBuilder("financialImportJob", repository).listener(listener).start(financialImportStep).build();
    }

    @Bean
    Step financialImportStep(JobRepository repository, PlatformTransactionManager transactionManager,
            CorpCodeReader corpCodeReader, FinancialImportProcessor financialProcessor,
            FinancialJdbcWriter writer, @Value("${corpscope.batch.chunk-size:5}") int chunkSize) {
        if (chunkSize < 1 || chunkSize > 100) throw new IllegalArgumentException("chunk-size must be 1..100");
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(8000);
        return new StepBuilder("financialImportStep", repository)
                .<String, CollectedCompany>chunk(chunkSize, transactionManager)
                .reader(corpCodeReader).processor(financialProcessor).writer(writer)
                .listener(financialProcessor)
                .faultTolerant().retry(DartException.Transient.class).retryLimit(3)
                .backOffPolicy(backoff).build();
    }

    @Bean
    @StepScope
    CorpCodeReader corpCodeReader(@Value("#{jobParameters['corpCodes']}") String codes) {
        return new CorpCodeReader(codes);
    }

    @Bean
    @StepScope
    FinancialImportProcessor financialProcessor(CompanyCollector collector,
            @Value("#{jobParameters['fiscalYear']}") Long year,
            @Value("#{jobParameters['reportType']}") String report,
            @Value("#{jobParameters['statementType']}") String statement) {
        if (year == null || year < 2015 || year > 2100) throw new IllegalArgumentException("Invalid fiscalYear");
        FinancialPeriod period = new FinancialPeriod(year.intValue(), ReportType.valueOf(report), StatementType.valueOf(statement));
        return new FinancialImportProcessor(collector, period);
    }
}
