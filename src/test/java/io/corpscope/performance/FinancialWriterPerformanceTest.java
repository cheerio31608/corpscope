package io.corpscope.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.corpscope.external.dart.DartCompanyResponse;
import io.corpscope.external.dart.DartFinancialResponse;
import io.corpscope.financial.*;
import io.corpscope.ingestion.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
import static org.assertj.core.api.Assertions.*;

/** No environment DB URL or HTTP client: every write targets this test's new PostgreSQL container. */
@Tag("performance")
@Testcontainers
class FinancialWriterPerformanceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("corpscope_performance");

    @Test void measuresFreshAndRepeatedImportsWithoutNetworkOrNormalizationTime() throws Exception {
        String input = System.getProperty("corpscope.performance.input", "");
        assertThat(input).as("Set -Dcorpscope.performance.input to an exported local JSON file").isNotBlank();
        Path inputPath = Path.of(input).toAbsolutePath();
        byte[] bytes = Files.readAllBytes(inputPath);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var transformer = new DartTransformer(new AccountStandardizer());
        var root = mapper.readTree(bytes);
        assertThat(root.isArray()).isTrue();
        var records = new ArrayList<CollectedCompany>();
        var keys = new HashSet<String>();
        for (var item : root) {
            String code = item.required("corpCode").asText();
            var period = new FinancialPeriod(item.required("fiscalYear").asInt(), ReportType.ANNUAL, StatementType.CFS);
            assertThat(keys.add(code + ":" + period.fiscalYear())).as("Duplicate company/year").isTrue();
            String companyJson = mapper.writeValueAsString(item.required("company"));
            String financialJson = mapper.writeValueAsString(item.required("financial"));
            var company = mapper.readValue(companyJson, DartCompanyResponse.class);
            var financial = mapper.readValue(financialJson, DartFinancialResponse.class);
            assertThat(financial.status()).isIn("000", "013");
            boolean noData = "013".equals(financial.status());
            var facts = noData ? List.<FinancialData>of() : transformer.financials(financial.list(), code, period);
            if (!noData) assertThat(facts).isNotEmpty();
            records.add(new CollectedCompany(transformer.company(company, code), period, facts,
                    companyJson, financialJson, noData, "OPENDART"));
        }
        assertThat(records).isNotEmpty();
        long expectedRows = records.stream().mapToLong(r -> r.financials().size()).sum();
        assertThat(expectedRows).isPositive();
        long expectedSnapshots = records.size() + records.stream().map(r -> r.company().corpCode()).distinct().count();
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        var samples = new ArrayList<Map<String, Object>>();
        try (var dataSource = new HikariDataSource(config)) {
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var writer = new FinancialJdbcWriter(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            // Warm up on the isolated DB only; not a recorded sample.
            writeAll(records, writer, transaction);
            for (int round = 1; round <= 3; round++) {
                // This JDBC connection is obtained solely from POSTGRES, never from the application's .env.
                jdbc.execute("TRUNCATE financial_fact, raw_ingestion, company RESTART IDENTITY CASCADE");
                samples.add(measure("fresh", round, records, writer, transaction, expectedRows));
                verify(jdbc, expectedRows, expectedSnapshots);
                String before = valueHash(jdbc);
                samples.add(measure("repeat", round, records, writer, transaction, expectedRows));
                verify(jdbc, expectedRows, expectedSnapshots);
                assertThat(valueHash(jdbc)).as("Idempotent values after repeat").isEqualTo(before);
            }
        }
        Path report = Path.of("build/reports/performance/financial-writer.json");
        Files.createDirectories(report.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "source", "OPENDART", "inputSha256", HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
                "periods", records.size(), "financialRows", expectedRows,
                "noDataPeriods", records.stream().filter(CollectedCompany::noFinancialData).count(),
                "jdbcBatchSize", 500, "periodsPerTransaction", 5, "samples", samples,
                "scope", "JDBC writer plus raw snapshots, upsert, period replacement and commit; excludes HTTP, normalization and Batch metadata"));
    }

    private Map<String, Object> measure(String mode, int round, List<CollectedCompany> records,
            FinancialJdbcWriter writer, TransactionTemplate transaction, long rows) {
        long start = System.nanoTime();
        writeAll(records, writer, transaction);
        long nanos = System.nanoTime() - start;
        return Map.of("mode", mode, "round", round, "elapsedMs", nanos / 1_000_000.0,
                "rowsPerSecond", rows * 1_000_000_000.0 / nanos);
    }

    private void writeAll(List<CollectedCompany> records, FinancialJdbcWriter writer, TransactionTemplate transaction) {
        for (int from = 0; from < records.size(); from += 5) {
            var chunk = new Chunk<>(records.subList(from, Math.min(from + 5, records.size())));
            transaction.executeWithoutResult(status -> writer.write(chunk));
        }
    }

    private void verify(JdbcTemplate jdbc, long rows, long snapshots) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact", Long.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion", Long.class)).isEqualTo(snapshots);
    }

    private String valueHash(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT md5(string_agg(concat_ws('|',company_id,fiscal_year,report_type,statement_type,
                  statement_division,account_key,standard_account,raw_account_name,account_detail,
                  coalesce(amount::text,'NULL'),coalesce(cumulative_amount::text,'NULL'),currency,
                  receipt_number,source,raw_ingestion_id),E'\n' ORDER BY company_id,fiscal_year,statement_division,account_key))
                FROM financial_fact
                """, String.class);
    }
}
