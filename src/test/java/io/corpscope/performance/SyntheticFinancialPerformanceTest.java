package io.corpscope.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.corpscope.financial.*;
import io.corpscope.ingestion.*;
import java.math.BigDecimal;
import java.nio.file.*;
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

/** All generated rows live in this test's disposable PostgreSQL, never the user's collection DB. */
@Tag("synthetic-performance")
@Testcontainers
class SyntheticFinancialPerformanceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("corpscope_synthetic_performance");

    @Test void measuresWriterAndRepresentativeReadPlans() throws Exception {
        int companies = Integer.parseInt(System.getProperty("corpscope.synthetic.companies", "100"));
        assertThat(companies).isBetween(100, 1000);
        long targetRows = companies * 1000L;
        var facts = new ArrayList<FinancialData>();
        for (int account = 0; account < 100; account++) {
            facts.add(new FinancialData("IS", "synthetic-" + account, "synthetic-" + account,
                    StandardAccount.UNMAPPED, "Synthetic account " + account, "",
                    BigDecimal.valueOf(account + 1), null, "KRW", "20260312000001"));
        }
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        var report = new LinkedHashMap<String, Object>();
        report.put("source", "SYNTHETIC");
        report.put("syntheticCompanies", companies);
        report.put("years", "2016..2025");
        report.put("accountsPerCompanyYear", 100);
        report.put("targetRows", targetRows);
        report.put("jdbcBatchSize", 500);
        report.put("companyPeriodsPerTransaction", 5);
        report.put("scope", "JDBC writer, raw snapshots, PostgreSQL commit; no HTTP, normalization, Batch metadata or HTTP timing");
        try (var dataSource = new HikariDataSource(config)) {
            Flyway.configure().dataSource(dataSource).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var writer = new FinancialJdbcWriter(jdbc);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            long firstStart = System.nanoTime();
            writeAll(writer, tx, facts, companies);
            double freshMs = elapsedMs(firstStart);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact", Long.class)).isEqualTo(targetRows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion", Long.class)).isEqualTo(companies * 11L);
            Long companyId = jdbc.queryForObject("SELECT id FROM company WHERE corp_code='90000050'", Long.class);
            assertThat(companyId).isNotNull();
            jdbc.execute("ANALYZE financial_fact");
            var periodPlan = plan(jdbc, companyId, true);
            var allPeriodsPlan = plan(jdbc, companyId, false);
            var before = jdbc.queryForObject("SELECT sum(amount) FROM financial_fact", BigDecimal.class);
            long repeatStart = System.nanoTime();
            writeAll(writer, tx, facts, companies);
            double repeatMs = elapsedMs(repeatStart);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_fact", Long.class)).isEqualTo(targetRows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_ingestion", Long.class)).isEqualTo(companies * 11L);
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM financial_fact", BigDecimal.class))
                    .isEqualByComparingTo(before);
            report.put("freshMs", freshMs);
            report.put("repeatMs", repeatMs);
            report.put("freshRowsPerSecond", targetRows * 1000.0 / freshMs);
            report.put("repeatRowsPerSecond", targetRows * 1000.0 / repeatMs);
            report.put("periodQueryExplain", new ObjectMapper().readTree(periodPlan));
            report.put("companyQueryExplain", new ObjectMapper().readTree(allPeriodsPlan));
            report.put("financialFactBytes", jdbc.queryForObject(
                    "SELECT pg_total_relation_size('financial_fact')", Long.class));
        }
        var output = Path.of("build/reports/performance/synthetic-financial-" + (targetRows / 1000) + "k.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private void writeAll(FinancialJdbcWriter writer, TransactionTemplate tx, List<FinancialData> facts, int companies) {
        for (int company = 0; company < companies; company++) {
            var c = new CompanyData(String.format("%08d", 90_000_000 + company),
                    "Synthetic company " + company, String.format("%06d", 900_000 + company),
                    null, null, null, null, null, null, null);
            for (int year = 2016; year <= 2025; year += 5) {
                var chunk = new ArrayList<CollectedCompany>();
                for (int current = year; current < year + 5; current++) {
                    chunk.add(new CollectedCompany(c, new FinancialPeriod(current, ReportType.ANNUAL, StatementType.CFS),
                            facts, "{\"synthetic\":true}", "{\"synthetic\":true,\"year\":" + current + "}",
                            false, "SYNTHETIC"));
                }
                tx.executeWithoutResult(status -> writer.write(new Chunk<>(chunk)));
            }
        }
    }

    private String plan(JdbcTemplate jdbc, long companyId, boolean period) {
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                SELECT f.* FROM financial_fact f WHERE f.company_id=?
                """ + (period ? " AND f.fiscal_year=2023 AND f.report_type='ANNUAL' AND f.statement_type='CFS' " : " ") + """
                ORDER BY f.fiscal_year DESC,f.report_type,f.statement_type,f.statement_division,f.account_key
                LIMIT 100
                """;
        return jdbc.queryForObject(sql, String.class, companyId);
    }

    private double elapsedMs(long start) {return (System.nanoTime() - start) / 1_000_000.0;}
}
