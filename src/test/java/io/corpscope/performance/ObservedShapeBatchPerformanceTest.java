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

/** Only observed period row counts are used; all companies, account names and values are synthetic. */
@Tag("observed-shape-performance")
@Testcontainers
class ObservedShapeBatchPerformanceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("corpscope_observed_shape");
    // Read-only count distribution from the 102 OPENDART company/year/report/statement periods on 2026-10-20.
    private static final String PERIOD_COUNTS = "144,147,149,152,153,158,161,161,162,162,164,165,169,169,170,171,171,171,173,174,176,178,179,179,179,180,180,181,185,186,188,191,192,193,193,194,194,195,197,197,201,205,207,208,209,210,212,212,213,215,217,218,218,218,218,218,218,219,222,224,226,228,228,229,230,233,236,237,237,238,239,239,240,242,243,248,252,256,256,260,260,262,263,264,265,266,273,276,276,277,280,282,283,285,303,307,312,320,401,410,429,445";
    private static final int REPLICATIONS = 5;

    @Test void comparesDriverRewriteForObservedPeriodSizes() throws Exception {
        int[] counts = java.util.Arrays.stream(PERIOD_COUNTS.split(",")).mapToInt(Integer::parseInt).toArray();
        assertThat(counts).hasSize(102);
        assertThat(java.util.Arrays.stream(counts).sum()).isEqualTo(22_801);
        List<CollectedCompany> records = records(counts);
        long expectedRows = 22_801L * REPLICATIONS;
        var report = new LinkedHashMap<String, Object>();
        report.put("source", "SYNTHETIC account values with 2026-10-20 OPENDART period row-count distribution");
        report.put("periodsPerReplication", counts.length);
        report.put("replications", REPLICATIONS);
        report.put("totalPeriods", records.size());
        report.put("targetRows", expectedRows);
        report.put("observedMinMedianMax", "144 / 217.5 / 445");
        report.put("jdbcBatchSize", 500);
        report.put("scope", "FinancialJdbcWriter plus commit; excludes API, transformation, Batch metadata and HTTP");
        var samples = new ArrayList<Object>();
        for (int round = 1; round <= 3; round++) {
            for (boolean rewrite : (round == 2 ? new boolean[]{true, false} : new boolean[]{false, true})) {
                samples.add(measure(round, rewrite, records, expectedRows));
            }
        }
        report.put("samples", samples);
        var output = Path.of("build/reports/performance/observed-shape-batch.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private Object measure(int round, boolean rewrite, List<CollectedCompany> records, long expectedRows) {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        config.addDataSourceProperty("reWriteBatchedInserts", rewrite);
        try (var dataSource = new HikariDataSource(config)) {
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("TRUNCATE financial_fact, raw_ingestion, company RESTART IDENTITY CASCADE");
            var writer = new FinancialJdbcWriter(jdbc);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            long start = System.nanoTime();
            for (int from = 0; from < records.size(); from += 5) {
                var chunk = new Chunk<>(records.subList(from, Math.min(from + 5, records.size())));
                tx.executeWithoutResult(status -> writer.write(chunk));
            }
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact", Long.class)).isEqualTo(expectedRows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion", Long.class)).isEqualTo(565L);
            var sample = new LinkedHashMap<String, Object>();
            sample.put("round", round);
            sample.put("reWriteBatchedInserts", rewrite);
            sample.put("elapsedMs", elapsedMs);
            sample.put("rowsPerSecond", expectedRows * 1000.0 / elapsedMs);
            return sample;
        }
    }

    private List<CollectedCompany> records(int[] counts) {
        var result = new ArrayList<CollectedCompany>();
        for (int copy = 0; copy < REPLICATIONS; copy++) {
            for (int periodIndex = 0; periodIndex < counts.length; periodIndex++) {
                int company = copy * 11 + periodIndex / 10;
                int year = 2016 + periodIndex % 10;
                var identity = new CompanyData(String.format("%08d", 90_000_000 + company),
                        "Synthetic company " + company, String.format("%06d", 900_000 + company),
                        null, null, null, null, null, null, null);
                var facts = new ArrayList<FinancialData>();
                for (int account = 0; account < counts[periodIndex]; account++) {
                    facts.add(new FinancialData("IS", "synthetic-" + account, "synthetic-" + account,
                            StandardAccount.UNMAPPED, "Synthetic account " + account, "",
                            BigDecimal.valueOf(account + 1), null, "KRW", "20260312000001"));
                }
                result.add(new CollectedCompany(identity,
                        new FinancialPeriod(year, ReportType.ANNUAL, StatementType.CFS), facts,
                        "{\"synthetic\":true}", "{\"synthetic\":true,\"year\":" + year + "}",
                        false, "SYNTHETIC"));
            }
        }
        return result;
    }
}
