package io.corpscope.operations;

import io.corpscope.common.NotFoundException;
import java.time.Duration;
import java.util.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BatchOperationsService {
    private static final Set<String> EXPECTED_PARAMETERS =
            Set.of("corpCodes", "fiscalYear", "reportType", "statementType", "requestId");
    private final JobExplorer explorer;
    private final JdbcTemplate jdbc;

    public BatchOperationsService(JobExplorer explorer, JdbcTemplate jdbc) {
        this.explorer = explorer;
        this.jdbc = jdbc;
    }

    public List<BatchExecutionReport> recent(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be 1..100");
        return jdbc.queryForList("SELECT job_execution_id FROM batch_job_execution ORDER BY job_execution_id DESC LIMIT ?",
                Long.class, limit).stream().map(this::execution).toList();
    }

    public BatchExecutionReport execution(long id) {
        JobExecution execution = explorer.getJobExecution(id);
        if (execution == null) throw new NotFoundException("Batch execution not found");
        var steps = execution.getStepExecutions().stream().sorted(Comparator.comparing(StepExecution::getId))
                .map(step -> new BatchExecutionReport.StepReport(step.getStepName(), step.getStatus().name(),
                        step.getReadCount(), step.getWriteCount(), step.getFilterCount(), step.getSkipCount(),
                        step.getCommitCount(), step.getRollbackCount(),
                        step.getExecutionContext().getInt("corpCodeReader.index", 0),
                        safeCorpCode(step.getExecutionContext().getString("failedCorpCode", "")),
                        step.getExecutionContext().getString("dataSource", "UNKNOWN")))
                .toList();
        Map<String, String> parameters = safeParameters(execution.getJobParameters());
        boolean failed = execution.getStatus() == BatchStatus.FAILED || execution.getStatus() == BatchStatus.STOPPED;
        boolean knownParameters = execution.getJobParameters().getParameters().keySet().equals(EXPECTED_PARAMETERS)
                && execution.getJobParameters().getParameters().values().stream().allMatch(JobParameter::isIdentifying)
                && execution.getJobParameters().getParameter("fiscalYear").getType() == Long.class;
        String source = steps.isEmpty() ? "UNKNOWN" : steps.getFirst().dataSource();
        boolean restartable = failed && knownParameters && parameters.size() == 5
                && "financialImportJob".equals(execution.getJobInstance().getJobName())
                && Set.of("OPENDART", "DEMO").contains(source);
        String restartCommand = restartable ? "./scripts/Import-Financials.ps1 -CorpCodes '" + parameters.get("corpCodes")
                + "' -FiscalYear " + parameters.get("fiscalYear") + " -ReportType " + parameters.get("reportType")
                + " -StatementType " + parameters.get("statementType") + " -RequestId '" + parameters.get("requestId")
                + "'" + ("DEMO".equals(source) ? " -Demo" : "") : null;
        var context = execution.getExecutionContext();
        Long elapsed = execution.getStartTime() == null || execution.getEndTime() == null ? null
                : Duration.between(execution.getStartTime(), execution.getEndTime()).toMillis();
        return new BatchExecutionReport(execution.getId(), execution.getJobInstance().getInstanceId(),
                execution.getJobInstance().getJobName(), execution.getStatus().name(),
                execution.getStartTime(), execution.getEndTime(), elapsed, parameters,
                context.getString("failureCategory", "UNKNOWN_LEGACY"),
                context.getString("failureSummary", "Legacy execution has no structured failure diagnostics"),
                restartable, restartCommand, steps);
    }

    private Map<String, String> safeParameters(JobParameters parameters) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (String name : List.of("corpCodes", "fiscalYear", "reportType", "statementType", "requestId")) {
            var parameter = parameters.getParameter(name);
            if (parameter == null) continue;
            String value = String.valueOf(parameter.getValue());
            boolean valid = switch (name) {
                case "corpCodes" -> value.length() <= 999 && value.matches("[0-9]{8}(,[0-9]{8}){0,99}");
                case "fiscalYear" -> value.matches("20[0-9]{2}|2100") && Integer.parseInt(value) >= 2015;
                case "reportType" -> Set.of("ANNUAL", "Q1", "HALF_YEAR", "Q3").contains(value);
                case "statementType" -> Set.of("CFS", "OFS").contains(value);
                case "requestId" -> value.matches("[a-zA-Z0-9._:-]{1,100}");
                default -> false;
            };
            if (valid) safe.put(name, value);
        }
        return Collections.unmodifiableMap(safe);
    }

    private String safeCorpCode(String code) { return code.matches("[0-9]{8}") ? code : null; }
}
