package io.corpscope.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.corpscope.financial.FinancialPeriod;
import io.corpscope.financial.ReportType;
import io.corpscope.financial.StandardAccount;
import io.corpscope.financial.StatementType;
import io.corpscope.ingestion.CollectedCompany;
import io.corpscope.ingestion.CompanyData;
import io.corpscope.ingestion.FinancialData;
import io.corpscope.ingestion.FinancialJdbcWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit benchmark only: every scenario uses the same disposable database and input shape. */
@Tag("batch-comparison")
@Testcontainers
class BatchInsertComparisonTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("corpscope_batch_comparison");
    private static final int COMPANIES = 20;
    private static final int YEARS = 10;
    private static final int ACCOUNTS = 500;
    private static final long TARGET_ROWS = (long) COMPANIES * YEARS * ACCOUNTS;

    @Test void comparesBatchSizeAndDriverRewrite() throws Exception {
        var facts = facts();
        var report = new LinkedHashMap<String, Object>();
        report.put("source", "SYNTHETIC");
        report.put("database", "disposable Testcontainers PostgreSQL 16.13");
        report.put("targetRows", TARGET_ROWS);
        report.put("companies", COMPANIES);
        report.put("years", "2016..2025");
        report.put("accountsPerPeriod", ACCOUNTS);
        report.put("companyPeriodsPerTransaction", 5);
        report.put("scope", "FinancialJdbcWriter: company/raw upserts, period delete, fact inserts, commits; no external calls or HTTP");
        var measurements = new ArrayList<Object>();
        // Reverse order in round two to expose some order/cache sensitivity.
        int[][] order = {{50, 100, 500}, {500, 100, 50}};
        for (int round = 0; round < order.length; round++) {
            for (int size : order[round]) {
                for (boolean rewrite : (round == 0 ? new boolean[]{false, true} : new boolean[]{true, false})) {
                    measurements.add(measure(round + 1, size, rewrite, facts));
                }
            }
        }
        report.put("measurements", measurements);
        var output = Path.of("build/reports/performance/batch-insert-comparison.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private Object measure(int round, int size, boolean rewrite, List<FinancialData> facts) {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        config.addDataSourceProperty("reWriteBatchedInserts", rewrite);
        try (var dataSource = new HikariDataSource(config)) {
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            // This database belongs only to the disposable container. Reset outside the timed section.
            jdbc.execute("TRUNCATE financial_fact, raw_ingestion, company RESTART IDENTITY CASCADE");
            var writer = new FinancialJdbcWriter(jdbc, size);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            long start = System.nanoTime();
            for (int company = 0; company < COMPANIES; company++) {
                var c = new CompanyData(String.format("%08d", 90_000_000 + company),
                        "Synthetic company " + company, String.format("%06d", 900_000 + company),
                        null, null, null, null, null, null, null);
                for (int year = 2016; year <= 2025; year += 5) {
                    var chunk = new ArrayList<CollectedCompany>();
                    for (int current = year; current < year + 5; current++) {
                        chunk.add(new CollectedCompany(c,
                                new FinancialPeriod(current, ReportType.ANNUAL, StatementType.CFS), facts,
                                "{\"synthetic\":true}", "{\"synthetic\":true,\"year\":" + current + "}",
                                false, "SYNTHETIC"));
                    }
                    tx.executeWithoutResult(status -> writer.write(new Chunk<>(chunk)));
                }
            }
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact", Long.class)).isEqualTo(TARGET_ROWS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion", Long.class))
                    .isEqualTo(COMPANIES * (YEARS + 1L));
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM financial_fact", BigDecimal.class))
                    .isEqualByComparingTo(BigDecimal.valueOf((long) COMPANIES * YEARS * ACCOUNTS * (ACCOUNTS + 1) / 2));
            var row = new LinkedHashMap<String, Object>();
            row.put("round", round);
            row.put("batchSize", size);
            row.put("reWriteBatchedInserts", rewrite);
            row.put("elapsedMs", elapsedMs);
            row.put("rowsPerSecond", TARGET_ROWS * 1000.0 / elapsedMs);
            return row;
        }
    }

    private List<FinancialData> facts() {
        var result = new ArrayList<FinancialData>();
        for (int account = 0; account < ACCOUNTS; account++) {
            result.add(new FinancialData("IS", "synthetic-" + account, "synthetic-" + account,
                    StandardAccount.UNMAPPED, "Synthetic account " + account, "",
                    BigDecimal.valueOf(account + 1), null, "KRW", "20260312000001"));
        }
        return result;
    }
}
