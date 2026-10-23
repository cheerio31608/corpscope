package io.corpscope.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.analysis.FinancialAnalysisService;
import io.corpscope.company.CompanyRepository;
import io.corpscope.external.dart.*;
import io.corpscope.financial.*;
import io.corpscope.ingestion.*;
import io.corpscope.operations.*;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(properties = "corpscope.batch.chunk-size=1")
@AutoConfigureMockMvc
class PostgresBatchIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13");

    @Autowired JobLauncher launcher;
    @Autowired Job financialImportJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyRepository companies;
    @Autowired FinancialFactRepository financials;
    @Autowired FinancialAnalysisService analysis;
    @Autowired CompanyCollector collector;
    @Autowired FinancialJdbcWriter writer;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired BatchOperationsService operations;
    @Autowired CollectionVerificationService verification;
    @MockitoSpyBean DemoDartClient gateway;

    @BeforeEach void clearDomainData() {
        jdbc.update("DELETE FROM report_publication");
        jdbc.update("DELETE FROM financial_fact");
        jdbc.update("DELETE FROM disclosure");
        jdbc.update("DELETE FROM raw_ingestion");
        jdbc.update("DELETE FROM company");
    }

    @Test void readinessSeparatesMissingAmountsFromMissingMappingAndDemoPublication() throws Exception {
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        jdbc.update("UPDATE financial_fact SET amount=NULL WHERE standard_account='NET_INCOME'");
        jdbc.update("UPDATE financial_fact SET standard_account='UNMAPPED' WHERE standard_account='EQUITY'");
        assertThat(jdbc.queryForObject("SELECT account_status FROM main_account_audit WHERE standard_account='NET_INCOME'", String.class))
                .isEqualTo("MISSING_AMOUNT");
        assertThat(jdbc.queryForObject("SELECT account_status FROM main_account_audit WHERE standard_account='EQUITY'", String.class))
                .isEqualTo("MISSING_MAPPING_OR_ACCOUNT");
        assertThat(jdbc.queryForObject("SELECT publication_status FROM financial_report_provenance", String.class))
                .isEqualTo("DEMO_NOT_VERIFIED");
        assertThat(jdbc.queryForObject("SELECT usable_after_kst_day_end FROM financial_report_provenance", java.sql.Timestamp.class))
                .isNull();
    }

    @Test void importsTwoYearsAndSupportsJpaQueriesAnalysisAndApi() throws Exception {
        assertThat(run(2023).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long id = companyId();
        assertThat(companies.searchByNamePrefix("corpscope%", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(financials.search(id, null, null, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(12);
        var result = analysis.analyze(id, new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(result.revenueGrowth()).isEqualByComparingTo("20");
        assertThat(result.roa()).isEqualByComparingTo("6.3158");
        assertThat(result.warnings()).isEmpty();
        mvc.perform(get("/api/companies/{id}/financials", id).param("fiscalYear", "2024").param("reportType", "ANNUAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].source").value("DEMO"));
        mvc.perform(get("/api/companies/{id}/financials", id)).andExpect(status().isOk());
        mvc.perform(get("/api/companies/{id}/disclosures", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/companies/{id}/disclosures", id).param("from", "2024-01-01"))
                .andExpect(status().isOk());
    }

    @Test void repeatedImportIsIdempotentAndCompletedInstanceCannotRunAgain() throws Exception {
        var parameters = params("00000001", 2024);
        assertThat(launcher.run(financialImportJob, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThatThrownBy(() -> launcher.run(financialImportJob, parameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("company")).isEqualTo(1);
        assertThat(count("financial_fact")).isEqualTo(6);
        assertThat(count("raw_ingestion")).isEqualTo(2);
    }

    @Test void customConsolidatedProfitLabelSurvivesBatchStorageVerificationAndAnalysis() throws Exception {
        assertThat(run(2023).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);
        var json = mapper.readTree(gateway.financials("00000001", period).rawJson());
        for (var row : json.get("list")) {
            if ("ifrs-full_ProfitLoss".equals(row.path("account_id").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                        .put("account_id", "-표준계정코드 미사용-").put("account_nm", "연결당기순이익");
            }
        }
        String raw = mapper.writeValueAsString(json);
        doReturn(new ApiPayload<>(mapper.readValue(raw, DartFinancialResponse.class), raw))
                .when(gateway).financials("00000001", period);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("financial_fact")).isEqualTo(12);
        assertThat(count("raw_ingestion")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM financial_fact WHERE fiscal_year=2024
                AND raw_account_name='연결당기순이익' AND standard_account='NET_INCOME'
                """, Integer.class)).isEqualTo(1);
        var verified = verification.verify("00000001", period);
        assertThat(verified.rawMatchesStoredFacts()).isTrue();
        assertThat(verified.errors()).isEmpty();
        assertThat(verified.analysis().roa()).isEqualByComparingTo("6.3158");
        assertThat(verified.analysis().roe()).isEqualByComparingTo("10.4348");
    }

    @Test void retriesTransientApiFailure() throws Exception {
        doThrow(new DartException.Transient("temporary outage")).doCallRealMethod()
                .when(gateway).financials(eq("00000001"), any(FinancialPeriod.class));
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(gateway, times(2)).financials(eq("00000001"), any(FinancialPeriod.class));
        assertThat(count("financial_fact")).isEqualTo(6);
    }

    @Test void legacyRevenueSurvivesBatchStorageReimportAndRawVerification() throws Exception {
        assertThat(run(2023).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);
        var json = mapper.readTree(gateway.financials("00000001", period).rawJson());
        for (var row : json.get("list")) {
            if ("ifrs-full_Revenue".equals(row.path("account_id").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                        .put("account_id", "ifrs_Revenue").put("account_nm", "수익(매출액)");
            }
        }
        String raw = mapper.writeValueAsString(json);
        doReturn(new ApiPayload<>(mapper.readValue(raw, DartFinancialResponse.class), raw))
                .when(gateway).financials("00000001", period);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("financial_fact")).isEqualTo(12);
        assertThat(count("raw_ingestion")).isEqualTo(3);
        var verified = verification.verify("00000001", period);
        assertThat(verified.rawMatchesStoredFacts()).isTrue();
        assertThat(verified.errors()).isEmpty();
        assertThat(verified.analysis().revenueGrowth()).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM financial_fact WHERE fiscal_year=2024 AND account_code='ifrs_Revenue'
                AND raw_account_name='수익(매출액)' AND standard_account='REVENUE' AND source='DEMO'
                """, Integer.class)).isEqualTo(1);
    }

    @Test void comparativeReviewSqlIncludesMissingCurrentAccounts() throws Exception {
        assertThat(run(2023).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        jdbc.update("UPDATE financial_fact SET standard_account='UNMAPPED' WHERE fiscal_year=2024 AND standard_account='REVENUE'");
        // Use the same review query with its source filter pointed at the explicitly labelled DEMO fixture.
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/sql/Review-AnnualComparatives.sql"))
                .replace(":'fiscal_year'", "2024").replace("'OPENDART'", "'DEMO'");
        var rows = jdbc.queryForList(sql);
        assertThat(rows).hasSize(6);
        var revenue = rows.stream().filter(r -> "REVENUE".equals(r.get("standard_account"))).findFirst().orElseThrow();
        assertThat(revenue.get("current_amount")).isNull();
        assertThat(revenue.get("comparison_status")).isEqualTo("MISSING_OR_AMBIGUOUS");
    }

    @Test void failedJobRestartsFromLastCommittedCompany() throws Exception {
        var parameters = params("00000001,00000002", 2024);
        long failedStart = System.nanoTime();
        var failed = launcher.run(financialImportJob, parameters);
        double failedMs = (System.nanoTime() - failedStart) / 1_000_000.0;
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        var failedReport = operations.execution(failed.getId());
        assertThat(failedReport.failureCategory()).isEqualTo("INVALID_INPUT");
        assertThat(failedReport.restartable()).isTrue();
        assertThat(failedReport.restartCommand()).contains("00000001,00000002", "-Demo");
        assertThat(failedReport.steps().getFirst().committedTargetIndex()).isEqualTo(1);
        assertThat(failedReport.steps().getFirst().failedCorpCode()).isEqualTo("00000002");
        assertThat(count("company")).isEqualTo(1);
        var secondCompany = gateway.company("00000001");
        var secondFinancial = gateway.financials("00000001", new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        String companyJson = secondCompany.rawJson().replace("00000001", "00000002");
        String financialJson = secondFinancial.rawJson().replace("00000001", "00000002");
        doReturn(new ApiPayload<>(mapper.readValue(companyJson, DartCompanyResponse.class), companyJson))
                .when(gateway).company("00000002");
        doReturn(new ApiPayload<>(mapper.readValue(financialJson, DartFinancialResponse.class), financialJson))
                .when(gateway).financials(eq("00000002"), any(FinancialPeriod.class));
        clearInvocations(gateway);
        long restartStart = System.nanoTime();
        var restarted = launcher.run(financialImportJob, parameters);
        double restartMs = (System.nanoTime() - restartStart) / 1_000_000.0;
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var restartedReport = operations.execution(restarted.getId());
        assertThat(restartedReport.failureCategory()).isEqualTo("NONE");
        assertThat(restartedReport.restartable()).isFalse();
        assertThat(restartedReport.steps().getFirst().failedCorpCode()).isNull();
        assertThat(restartedReport.steps().getFirst().committedTargetIndex()).isEqualTo(2);
        verify(gateway, never()).company("00000001");
        assertThat(count("company")).isEqualTo(2);
        assertThat(count("financial_fact")).isEqualTo(12);
        // Diagnostic only: one tiny DEMO job with a controlled failure, not a throughput claim.
        var output = Path.of("build/reports/performance/batch-restart-demo.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "source", "DEMO", "database", "disposable Testcontainers PostgreSQL 16.13",
                "scenario", "two companies, chunk size one, second company fails before first restart",
                "failedExecutionMs", failedMs, "restartExecutionMs", restartMs,
                "committedTargetIndexBeforeRestart", 1, "committedTargetIndexAfterRestart", 2,
                "finalFinancialRows", 12));
    }

    @Test void databaseConstraintFailureRollsBackPeriodReplacementAndSnapshots() throws Exception {
        run(2024);
        var original = collector.collect("00000001", new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        var duplicate = original.financials().getFirst();
        var invalid = new CollectedCompany(original.company(), original.period(), List.of(duplicate, duplicate),
                original.companyJson(), original.financialJson(), false, original.source());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> writer.write(new Chunk<>(List.of(invalid)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("financial_fact")).isEqualTo(6);
        assertThat(count("raw_ingestion")).isEqualTo(2);
    }

    @Test void noDataResponsePreservesPreviouslyCollectedPeriod() throws Exception {
        run(2024);
        doReturn(new ApiPayload<>(new DartFinancialResponse("013", "no data", null), "{\"status\":\"013\"}"))
                .when(gateway).financials(eq("00000001"), any(FinancialPeriod.class));
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("financial_fact")).isEqualTo(6);
        assertThat(count("raw_ingestion")).isEqualTo(3);
    }

    @Test void correctedResponseRemovesDisappearedAccountsAndRetainsOriginalSnapshot() throws Exception {
        run(2024);
        var period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);
        var raw = mapper.readTree(gateway.financials("00000001", period).rawJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode) raw.get("list")).remove(5);
        String corrected = mapper.writeValueAsString(raw);
        doReturn(new ApiPayload<>(mapper.readValue(corrected, DartFinancialResponse.class), corrected))
                .when(gateway).financials(eq("00000001"), any(FinancialPeriod.class));
        assertThat(run(2024).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("financial_fact")).isEqualTo(5);
        assertThat(count("raw_ingestion")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact WHERE standard_account='EQUITY'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT raw_ingestion_id) FROM financial_fact", Integer.class))
                .isEqualTo(1);
    }

    @Test void disclosureDateQueryAndApiValidationUseActualPostgres() throws Exception {
        run(2024);
        Long id = companyId();
        jdbc.update("INSERT INTO disclosure(company_id,receipt_number,title,disclosure_type,receipt_date,report_code,original_url) VALUES (?,?,?,?,?,?,?)",
                id, "20250401000001", "가상 사업보고서", "REGULAR", LocalDate.of(2025, 4, 1), "11011", "https://example.invalid/demo");
        mvc.perform(get("/api/companies/{id}/disclosures", id).param("from", "2025-01-01").param("to", "2025-12-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/companies/{id}/financials", id).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies/{id}/financials", id).param("reportType", "wrong")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies/{id}/analysis", id).param("fiscalYear", "2024").param("reportType", "Q1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies/{id}/disclosures", id).param("from", "2026-01-01").param("to", "2025-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/companies/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/companies/search").param("name", "%")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private JobExecution run(long year) throws Exception { return launcher.run(financialImportJob, params("00000001", year)); }

    @Test void verificationComparesRawStoredValuesAndAnnualMetrics() throws Exception {
        run(2023);
        var execution = run(2024);
        var checked = verification.verify("00000001", new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(checked.status()).isEqualTo("PASS");
        assertThat(checked.rawMatchesStoredFacts()).isTrue();
        assertThat(checked.analysis().roe()).isEqualByComparingTo("10.4348");
        assertThat(checked.errors()).isEmpty();
        var status = operations.execution(execution.getId());
        assertThat(status.failureCategory()).isEqualTo("NONE");
        assertThat(status.steps().getFirst().companiesWritten()).isEqualTo(1);
        assertThat(status.steps().getFirst().dataSource()).isEqualTo("DEMO");
        assertThat(operations.recent(2)).hasSize(2);
    }

    @Test void verificationDetectsStoredValueTamperingWithoutRepairingIt() throws Exception {
        run(2024);
        jdbc.update("UPDATE financial_fact SET amount=amount+1 WHERE standard_account='REVENUE'");
        var checked = verification.verify("00000001", new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS));
        assertThat(checked.status()).isEqualTo("FAIL");
        assertThat(checked.rawMatchesStoredFacts()).isFalse();
        assertThat(checked.errors()).anyMatch(error -> error.contains("field mismatch"));
        assertThat(jdbc.queryForObject("SELECT amount FROM financial_fact WHERE standard_account='REVENUE'", java.math.BigDecimal.class))
                .isEqualByComparingTo("1201");
    }

    @Test void quarterlySyntheticFixtureKeepsYtdMissingValuesAndUnmappedAccounts() throws Exception {
        fixtureGateway("financials-half-year.json");
        var execution = launcher.run(financialImportJob, halfYearParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var checked = verification.verify("00000003", new FinancialPeriod(2024, ReportType.HALF_YEAR, StatementType.CFS));
        assertThat(checked.status()).isEqualTo("REVIEW");
        assertThat(checked.rawMatchesStoredFacts()).isTrue();
        assertThat(checked.financialRows()).isEqualTo(8);
        assertThat(checked.missingAmountRows()).isEqualTo(3);
        assertThat(checked.unmappedRows()).isEqualTo(4);
        assertThat(checked.analysis()).isNull();
        var revenue = financials.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(checked.companyId(),
                2024, ReportType.HALF_YEAR, StatementType.CFS).stream()
                .filter(row -> "IS".equals(row.getStatementDivision()) && row.getStandardAccount() == StandardAccount.REVENUE)
                .findFirst().orElseThrow();
        assertThat(revenue.getAmount()).isEqualByComparingTo("12340");
        assertThat(revenue.getCumulativeAmount()).isEqualByComparingTo("34560");
    }

    @Test void duplicateRawAccountsFailBeforeStorageWithUsefulPersistedDiagnostics() throws Exception {
        fixtureGateway("financials-duplicate.json");
        var execution = launcher.run(financialImportJob, halfYearParams());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(count("company")).isZero();
        assertThat(count("financial_fact")).isZero();
        var status = operations.execution(execution.getId());
        assertThat(status.failureCategory()).isEqualTo("DATA_QUALITY_DUPLICATE_ACCOUNT");
        assertThat(status.steps().getFirst().failedCorpCode()).isEqualTo("00000003");
        assertThat(status.steps().getFirst().committedTargetIndex()).isZero();
    }

    @Test void exhaustedTransientRetriesHavePersistedCategoryAndNoPartialData() throws Exception {
        doThrow(new DartException.Transient("connection timeout")).when(gateway)
                .financials(eq("00000001"), any(FinancialPeriod.class));
        var execution = run(2024);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(gateway, times(3)).financials(eq("00000001"), any(FinancialPeriod.class));
        assertThat(operations.execution(execution.getId()).failureCategory()).isEqualTo("EXTERNAL_TRANSIENT");
        assertThat(count("company")).isZero();
    }

    @Test void quotaFailureIsNotRetriedAndReportDoesNotContainRawException() throws Exception {
        doThrow(new DartException("DART rejected request (status 020)")).when(gateway)
                .financials(eq("00000001"), any(FinancialPeriod.class));
        var execution = run(2024);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(gateway, times(1)).financials(eq("00000001"), any(FinancialPeriod.class));
        var report = operations.execution(execution.getId());
        assertThat(report.failureCategory()).isEqualTo("DART_STATUS_020");
        assertThat(mapper.writeValueAsString(report)).doesNotContain("crtfc_key", "exitDescription", "stackTrace");
        assertThat(count("raw_ingestion")).isZero();
    }

    private JobParameters halfYearParams() {
        return new JobParametersBuilder().addString("corpCodes", "00000003").addLong("fiscalYear", 2024L)
                .addString("reportType", "HALF_YEAR").addString("statementType", "CFS")
                .addString("requestId", UUID.randomUUID().toString()).toJobParameters();
    }

    @Test void unknownIdentifyingParametersSuppressRestartCommandAndAreNotReported() throws Exception {
        var parameters = new JobParametersBuilder(params("00000001", 2022))
                .addString("unrelatedMetadata", "do-not-echo-arbitrary-parameters").toJobParameters();
        var execution = launcher.run(financialImportJob, parameters);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        var report = operations.execution(execution.getId());
        assertThat(report.restartable()).isFalse();
        assertThat(report.restartCommand()).isNull();
        assertThat(mapper.writeValueAsString(report)).doesNotContain("unrelatedMetadata", "do-not-echo-arbitrary-parameters");
    }

    private void fixtureGateway(String financialFile) throws Exception {
        try (var companyStream = getClass().getResourceAsStream("/fixtures/company-edge.json");
             var financialStream = getClass().getResourceAsStream("/fixtures/" + financialFile)) {
            String companyJson = new String(companyStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String financialJson = new String(financialStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            doReturn(new ApiPayload<>(mapper.readValue(companyJson, DartCompanyResponse.class), companyJson))
                    .when(gateway).company("00000003");
            doReturn(new ApiPayload<>(mapper.readValue(financialJson, DartFinancialResponse.class), financialJson))
                    .when(gateway).financials(eq("00000003"), any(FinancialPeriod.class));
        }
    }

    private JobParameters params(String codes, long year) {
        return new JobParametersBuilder().addString("corpCodes", codes).addLong("fiscalYear", year)
                .addString("reportType", "ANNUAL").addString("statementType", "CFS")
                .addString("requestId", UUID.randomUUID().toString()).toJobParameters();
    }
    private Long companyId() { return jdbc.queryForObject("SELECT id FROM company WHERE corp_code='00000001'", Long.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
