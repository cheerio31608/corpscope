package io.corpscope.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration") @Testcontainers @ActiveProfiles("demo") @SpringBootTest
class KrxStagingIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.13");
    @Autowired JdbcTemplate jdbc;@Autowired KrxStagingStore store;@Autowired KrxArchiveImportService importer;
    @Autowired KrxReturnArithmeticAudit returns;
    @Autowired KrxStagingAuditService audit;@Autowired ObjectMapper mapper;
    @TempDir Path folder;
    final LocalDate day=LocalDate.of(2025,3,19);final Instant collected=Instant.parse("2026-10-14T00:00:00Z");
    @BeforeEach void clean() {
        jdbc.execute("ALTER TABLE krx_price_quote_staging DROP CONSTRAINT IF EXISTS synthetic_fail");
        jdbc.update("DELETE FROM krx_price_quote_staging");jdbc.update("DELETE FROM krx_price_snapshot");
    }
    KrxStagingStore.Result save(byte[] bytes) {return store.save("stk_bydd_trd",day,KrxStagingStore.hash(bytes),collected,bytes);}
    @Test void retrospectiveArithmeticUsesExactly63ObservedDatesAndCannotBecomeApprovedLabels() throws Exception {
        // Synthetic isolated-container rows, deliberately not a real trading calendar or real company.
        jdbc.update("INSERT INTO company(corp_code,name,stock_code) VALUES ('99887766','Synthetic arithmetic','999998') ON CONFLICT DO NOTHING");
        long company=jdbc.queryForObject("SELECT id FROM company WHERE stock_code='999998'",Long.class);
        var first=LocalDate.of(2025,1,1);
        for(int i=0;i<65;i++) {
            var date=first.plusDays(i);var row=KrxSnapshotParserTest.row("999998","12");
            row.put("BAS_DD",date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
            var bytes=KrxSnapshotParserTest.bytes(row);store.save("stk_bydd_trd",date,KrxStagingStore.hash(bytes),collected,bytes);
        }
        var result=returns.review(new KrxReturnArithmeticAudit.Request(company,first,first.plusDays(64)));
        assertThat(result.calculated()).isEqualTo(1);assertThat(result.withheld()).hasSize(64);
        var row=result.rows().getFirst();
        assertThat(row.observedEntryDate()).isEqualTo(first.plusDays(2));
        assertThat(row.observedExitDate()).isEqualTo(first.plusDays(64));
        assertThat(row.observedPriceChangePercent()).isEqualByComparingTo("20");
        assertThat(row.financialStatus()).isEqualTo("NO_PUBLIC_FILING");
        assertThat(row.financialReceipt()).isNull();assertThat(row.approvedForDataset()).isFalse();
        assertThat(row.entryCollectedAt()).isEqualTo(collected);assertThat(result.forecastReady()).isFalse();
        var empty=KrxSnapshotParserTest.bytes();
        store.save("stk_bydd_trd",first.plusDays(64),KrxStagingStore.hash(empty),collected.plusSeconds(1),empty);
        assertThat(returns.review(new KrxReturnArithmeticAudit.Request(company,first,first.plusDays(64))).calculated()).isZero();
    }
    @Test void duplicateImportKeepsOriginalAndDoesNotApprovePublicationCalendarOrActions() throws Exception {
        var bytes=KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","12"));
        var first=save(bytes);
        var repeated=store.save("stk_bydd_trd",day,KrxStagingStore.hash(bytes),collected.plusSeconds(3600),bytes);
        assertThat(repeated.snapshotId()).isEqualTo(first.snapshotId());assertThat(repeated.inserted()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM krx_price_quote_staging",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price_basis FROM krx_price_snapshot",String.class)).isEqualTo("UNADJUSTED");
        assertThat(jdbc.queryForObject("SELECT official_publication_at IS NULL AND NOT calendar_verified AND NOT corporate_actions_verified FROM krx_price_snapshot",Boolean.class)).isTrue();
        assertThat((byte[])jdbc.queryForMap("SELECT payload_utf8 FROM krx_price_snapshot").get("payload_utf8")).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT collected_at FROM krx_price_snapshot",OffsetDateTime.class).toInstant())
                .isEqualTo(collected);
    }
    @Test void changedResponseBecomesAnotherSnapshotAndOldAmountsRemain() throws Exception {
        var first=save(KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","12")));
        var second=save(KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","13")));
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(jdbc.queryForObject("SELECT close_price FROM krx_price_quote_staging WHERE snapshot_id=?",java.math.BigDecimal.class,first.snapshotId())).isEqualByComparingTo("12");
        assertThat(audit.audit(new KrxStagingAuditService.Request(day,day,List.of("005930"))).multipleSnapshotDates()).isEqualTo(1);
    }
    @Test void newestEmptySnapshotCannotFallBackToOlderNonemptyQuotes() throws Exception {
        save(KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","12")));
        var empty=KrxSnapshotParserTest.bytes();
        store.save("stk_bydd_trd",day,KrxStagingStore.hash(empty),collected.plusSeconds(1),empty);
        var report=audit.audit(new KrxStagingAuditService.Request(day,day,List.of("005930")));
        assertThat(report.emptyResponseDates()).containsExactly(day);
        assertThat(report.observedNonemptyDates()).isZero();
        assertThat(report.companies().getFirst().observedRows()).isZero();
        assertThat(report.stagedQuotes()).isZero();
    }
    @Test void jdbcBatchFailureRollsBackBothSnapshotAndAllQuoteRows() throws Exception {
        jdbc.execute("ALTER TABLE krx_price_quote_staging ADD CONSTRAINT synthetic_fail CHECK(stock_code<>'999999')");
        var bytes=KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("000001","12"),KrxSnapshotParserTest.row("999999","12"));
        assertThatThrownBy(() -> save(bytes)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM krx_price_snapshot",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM krx_price_quote_staging",Integer.class)).isZero();
    }
    @Test void alteredStoredNumberIsDetectedInsteadOfSilentlyRepaired() throws Exception {
        var bytes=KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","12"));save(bytes);
        jdbc.update("UPDATE krx_price_quote_staging SET close_price=13");
        assertThatThrownBy(() -> save(bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT close_price FROM krx_price_quote_staging",java.math.BigDecimal.class)).isEqualByComparingTo("13");
    }
    @Test void metadataHashAndUnexpectedApprovalFlagsAreRejectedBeforeWrites() throws Exception {
        var bytes=KrxSnapshotParserTest.bytes(KrxSnapshotParserTest.row("005930","12"));var hash=KrxStagingStore.hash(bytes);
        String name="stk_bydd_trd-20250319-"+hash+".json";Files.write(folder.resolve(name),bytes);
        var meta=mapper.createObjectNode();meta.put("source","KRX");meta.put("api","stk_bydd_trd");meta.put("requestedDate","20250319");
        meta.put("sha256",hash);meta.put("file",name);meta.put("fetchedAt",collected.toString());meta.put("rows",1);
        meta.put("priceBasis","UNVERIFIED_RAW_API_PRICE");meta.putNull("officialPublicationAt");
        meta.put("calendarVerified",false);meta.put("corporateActionsVerified",false);meta.put("forecastReady",true);
        var path=folder.resolve(name+".metadata.json");Files.writeString(path,mapper.writeValueAsString(meta));
        assertThatThrownBy(() -> importer.importDirectory(folder.toString())).isInstanceOf(IllegalArgumentException.class);
        meta.put("forecastReady",false);Files.writeString(path,mapper.writeValueAsString(meta));
        assertThat(importer.importDirectory(folder.toString()).insertedSnapshots()).isEqualTo(1);
        assertThat(importer.importDirectory(folder.toString()).insertedSnapshots()).isZero();
        Files.writeString(folder.resolve(name),"{}");
        assertThatThrownBy(() -> importer.importDirectory(folder.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void auditSeparatesEmptyResponsesMissingDatesMissingStocksAndUnusablePrices() throws Exception {
        save(KrxSnapshotParserTest.bytes());
        var d=day.plusDays(1);var row=KrxSnapshotParserTest.row("005930","12");row.put("BAS_DD","20250320");row.put("TDD_OPNPRC","0");
        var bytes=KrxSnapshotParserTest.bytes(row);store.save("stk_bydd_trd",d,KrxStagingStore.hash(bytes),collected,bytes);
        var report=audit.audit(new KrxStagingAuditService.Request(day,day.plusDays(2),List.of("005930","000660")));
        assertThat(report.emptyResponseDates()).containsExactly(day);assertThat(report.missingResponseDates()).containsExactly(day.plusDays(2));
        assertThat(report.companies().get(0).unavailableOhlcOrVolume()).containsExactly(d);
        assertThat(report.companies().get(1).missingOnNonemptyDates()).containsExactly(d);
        assertThat(report.approvedTrainingRows()).isZero();assertThat(report.forecastReady()).isFalse();
        assertThat(report.calendarDateReview().unexpectedEmptyDates()).containsExactly(day);
        assertThat(report.calendarDateReview().missingResponses()).containsExactly(day.plusDays(2));
        assertThat(report.calendarDateReview().sessionTimesReviewed()).isFalse();
    }
    @Test void officialScheduledExceptionDoesNotPromoteStoredCalendarOrHistoricalAvailability() throws Exception {
        var date=LocalDate.of(2025,11,13);
        var row=KrxSnapshotParserTest.row("005930","12");row.put("BAS_DD","20251113");
        var bytes=KrxSnapshotParserTest.bytes(row);
        store.save("stk_bydd_trd",date,KrxStagingStore.hash(bytes),collected,bytes);
        var report=audit.audit(new KrxStagingAuditService.Request(date,date,List.of("005930")));
        assertThat(report.partialMarketEvidence().sessionNotices()).singleElement().satisfies(n -> {
            assertThat(n.regularOpen()).isEqualTo(LocalTime.of(10,0));
            assertThat(n.regularClose()).isEqualTo(LocalTime.of(16,30));
        });
        assertThat(report.partialMarketEvidence().historicalPublicationTimesReviewed()).isFalse();
        assertThat(report.partialMarketEvidence().approvedForDataset()).isFalse();
        assertThat(report.calendarDateReview().sessionTimesReviewed()).isFalse();
        assertThat(report.approvedTrainingRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT calendar_verified FROM krx_price_snapshot WHERE trading_date=?",Boolean.class,date)).isFalse();
        assertThat(jdbc.queryForObject("SELECT collected_at FROM krx_price_snapshot WHERE trading_date=?",OffsetDateTime.class,date).toInstant()).isEqualTo(collected);
    }
}
