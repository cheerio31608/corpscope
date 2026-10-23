package io.corpscope.batch;

import io.corpscope.financial.FinancialPeriod;
import io.corpscope.ingestion.CollectedCompany;
import io.corpscope.ingestion.CompanyCollector;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

public class FinancialImportProcessor implements ItemProcessor<String, CollectedCompany>, StepExecutionListener {
    private final CompanyCollector collector;
    private final FinancialPeriod period;
    private StepExecution stepExecution;

    public FinancialImportProcessor(CompanyCollector collector, FinancialPeriod period) {
        this.collector = collector;
        this.period = period;
    }

    @Override
    public CollectedCompany process(String corpCode) {
        try {
            CollectedCompany result = collector.collect(corpCode, period);
            stepExecution.getExecutionContext().remove("failedCorpCode");
            return result;
        } catch (RuntimeException ex) {
            stepExecution.getExecutionContext().putString("failedCorpCode", corpCode);
            throw ex;
        }
    }

    @Override
    public void beforeStep(StepExecution execution) {
        stepExecution = execution;
        execution.getExecutionContext().putString("dataSource", collector.source());
    }
}
