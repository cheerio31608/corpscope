package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.common.NotFoundException;
import io.corpscope.external.dart.DartGateway;
import io.corpscope.financial.*;
import io.corpscope.operations.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic document fixtures in an isolated DB, never imported as production evidence. */
@Tag("integration") @Testcontainers @ActiveProfiles("demo") @SpringBootTest @AutoConfigureMockMvc
class ReportVersionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.13");
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FinancialAsOfService queries;
    @Autowired FinancialFeatureService features;
    @Autowired FinancialFeatureExportService exports;
    @Autowired HistoricalCoverageService coverage;
    @Autowired SamsungReportReviewService samsungReviews;
    @Autowired HynixReportReviewService hynixReviews;
    @Autowired KepcoReportReviewService kepcoReviews;
    @Autowired NaverReportReviewService naverReviews;
    @Autowired FinancialPeriodComparisonService comparisons;
    @Autowired io.corpscope.dataset.LatestAnnualFinancialService latestAnnual;
    @Autowired io.corpscope.dataset.DatasetReadinessService datasetReadiness;
    @Autowired io.corpscope.dataset.TemporalDatasetBuilder datasetBuilder;
    @Autowired MockMvc mvc;
    ReportVersionCollectionService collector;
    long company;
    Map<String,byte[]> documents;

    @Test void latestAnnualBlocksNewUnreviewedYearInsteadOfUsingOlderReviewedPeriod() throws Exception {
        saveDistinctVersions();
        var before=latestAnnual.latest(company,LocalDate.of(2017,6,1));
        assertThat(before.fiscalYear()).isEqualTo(2016);
        assertThat(before.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("100000000");
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,company);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20180401000001',DATE '2018-04-01','사업보고서 (2017.12)',false,?)
            """,company,raw);
        assertThatThrownBy(() -> latestAnnual.latest(company,LocalDate.of(2018,4,2)))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("not been reviewed");
        var unchanged=latestAnnual.latest(company,LocalDate.of(2017,6,1));
        assertThat(unchanged.values()).isEqualTo(before.values());
        assertThat(unchanged.evidence()).isEqualTo(before.evidence());
    }
    @Test void latestAnnualSelectsActualCorrectionValuesAndRejectsItsMissingTotals() throws Exception {
        saveDistinctVersions();
        var corrected=latestAnnual.latest(company,LocalDate.of(2022,2,18));
        assertThat(corrected.evidence().receiptNumber()).isEqualTo("20220217000946");
        assertThat(corrected.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("200000000");
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE receipt_number='20220217000946')");
        assertThatThrownBy(() -> latestAnnual.latest(company,LocalDate.of(2022,2,18)))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("incomplete");
    }
    @Test void datasetReadinessDoesNotCallPartialFinancialsOrRepeatedDatesModelReady() throws Exception {
        saveDistinctVersions();
        var result=datasetReadiness.review(LocalDate.of(2022,2,18));
        assertThat(result.statusCounts()).containsEntry("PARTIAL_FINANCIAL_CANDIDATE",1L);
        assertThat(result.independentFullMetricPeriods()).isZero();
        assertThat(result.realLabeledSamples()).isZero();assertThat(result.forecastReady()).isFalse();
        assertThat(result.periods().getFirst().missingMetrics()).contains("revenueGrowth","roa","roe");
    }
    @Test void actualFinancialResolverAndSyntheticPricesStayUnchangedWhenFutureFinancialValuesChange() throws Exception {
        long id=prepareNaver2021(false);naverReviews.review(2022);
        jdbc.update("UPDATE company SET stock_code='035420' WHERE id=?",id);
        var date=LocalDate.of(2023,3,15);
        var sessions=new java.util.ArrayList<io.corpscope.dataset.TemporalDatasetBuilder.Session>();
        var prices=new java.util.ArrayList<io.corpscope.dataset.TemporalDatasetBuilder.Quote>();
        var kst=io.corpscope.dataset.TemporalSplitPolicy.KST;
        for(int i=0;i<110;i++) {
            var d=date.minusDays(10).plusDays(i); // Synthetic calendar, including weekends: explicit DEMO only.
            var open=d.atTime(9,0).atZone(kst).toInstant();var close=d.atTime(15,30).atZone(kst).toInstant();
            sessions.add(new io.corpscope.dataset.TemporalDatasetBuilder.Session(d,open,close));
            prices.add(new io.corpscope.dataset.TemporalDatasetBuilder.Quote(d,java.math.BigDecimal.valueOf(100+i),java.math.BigDecimal.valueOf(101+i),java.math.BigDecimal.valueOf(1000),close.plusSeconds(7200)));
        }
        var panel=new io.corpscope.dataset.TemporalDatasetBuilder.Prices("DEMO","035420","KRW","UNADJUSTED",true,
            sessions.getFirst().date(),sessions.getLast().date(),java.util.List.of(),sessions,prices);
        var request=new io.corpscope.dataset.TemporalDatasetBuilder.Request(id,java.util.List.of(date),panel,
            new io.corpscope.dataset.TemporalSplitPolicy(LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),java.time.Instant.parse("2026-01-01T00:00:00Z")));
        var before=datasetBuilder.build(request);assertThat(before.exported()).isEqualTo(1);
        assertThat(before.rows().getFirst().evidence().financialReceipt()).isEqualTo("20230314001049");
        assertThat(before.rows().getFirst().modelInputs().get("financial_revenue")).isEqualByComparingTo("8220078708810");
        naverReviews.review(2023);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        var after=datasetBuilder.build(request);
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(after.forecastReady()).isFalse();
    }

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM financial_same_day_main_review");
        jdbc.update("DELETE FROM financial_attachment_correction_review");
        jdbc.update("DELETE FROM financial_period_comparison_review");
        jdbc.update("DELETE FROM financial_version_fact"); jdbc.update("DELETE FROM financial_report_version");
        jdbc.update("DELETE FROM report_document"); jdbc.update("DELETE FROM report_publication");
        jdbc.update("DELETE FROM financial_fact");
        jdbc.update("DELETE FROM raw_ingestion"); jdbc.update("DELETE FROM company");
        company=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00164742','Synthetic version test') RETURNING id",Long.class);
        Long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,company,"0".repeat(64));
        for (String receipt:ReportVersionCollectionService.RECEIPTS) {
            boolean first=receipt.equals("20170331004820");
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,?,?)
                """,company,receipt,first ? LocalDate.of(2017,3,31):LocalDate.of(2022,2,17),
                first ? "사업보고서 (2016.12)":"[기재정정]사업보고서 (2016.12)",!first,raw);
        }
        String fixture=Hyundai2016ReportParserTest.fixture();
        documents=Map.of("20170331004820",Hyundai2016ReportParserTest.zip("20170331004820",fixture,StandardCharsets.UTF_8),
                "20220217000946",Hyundai2016ReportParserTest.zip("20220217000946",fixture,StandardCharsets.UTF_8));
        collector=new ReportVersionCollectionService(mock(DartGateway.class),new Hyundai2016ReportParser(),jdbc,mapper,transactions);
    }

    @Test void hyundaiMotor2024UsesReviewed2023CurrentTotalsWithoutLifting2023Hold() {
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,company);
        String previous="20240313001451", current="20250312001148";
        for(var filing:Map.of(previous,LocalDate.of(2024,3,13),current,LocalDate.of(2025,3,12)).entrySet()) {
            int year=filing.getKey().equals(previous) ? 2023:2024;
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,? ,false,?)
                """,company,filing.getKey(),filing.getValue(),"사업보고서 ("+year+".12)",raw);
            byte[] document=filing.getKey().getBytes(StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                company,filing.getKey(),ReportVersionCollectionService.sha256(document),document);
            long version=jdbc.queryForObject("""
                INSERT INTO financial_report_version(company_id,fiscal_year,report_type,statement_type,
                    receipt_number,main_entry,document_encoding,extraction_profile,evidence)
                VALUES (?,?,'ANNUAL','CFS',?,'synthetic.xml','UTF-8',?,CAST('{}' AS jsonb)) RETURNING id
                """,Long.class,company,year,filing.getKey(),"HYUNDAI_MOTOR_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1");
            for(StandardAccount account:StandardAccount.values()) {
                if(account==StandardAccount.UNMAPPED) continue;
                String division=switch(account) {
                    case ASSETS,LIABILITIES,EQUITY -> "BS";
                    default -> "IS";
                };
                long amount=switch(account) {
                    case ASSETS -> year==2023 ? 300:330;
                    case LIABILITIES -> year==2023 ? 200:220;
                    case EQUITY -> year==2023 ? 100:110;
                    case REVENUE -> year==2023 ? 100:120;
                    case OPERATING_PROFIT -> year==2023 ? 10:12;
                    case NET_INCOME -> year==2023 ? 8:9;
                    default -> throw new IllegalStateException();
                };
                jdbc.update("""
                    INSERT INTO financial_version_fact(version_id,standard_account,raw_account_name,
                        statement_division,amount,currency,display_unit)
                    VALUES (?,?,?,?,?,'KRW',1)
                    """,version,account.name(),account.name(),division,amount);
            }
        }
        long currentId=jdbc.queryForObject("SELECT id FROM financial_report_version WHERE receipt_number=?",Long.class,current);
        long previousId=jdbc.queryForObject("SELECT id FROM financial_report_version WHERE receipt_number=?",Long.class,previous);
        String evidence="""
            {"previousPeriodExclusionReason":"%s","comparativeTotals":
            {"ASSETS":300,"LIABILITIES":200,"EQUITY":100,"REVENUE":100,"OPERATING_PROFIT":10,"NET_INCOME":8}}
            """.formatted(FinancialPeriodComparisonService.HYUNDAI_MOTOR_2023_COMPARISON_HOLD);
        jdbc.update("""
            INSERT INTO financial_period_comparison_review(current_version_id,previous_version_id,
                comparison_profile,evidence_available_from_kst_date,evidence)
            VALUES (?,?,?,DATE '2025-03-13',CAST(? AS jsonb))
            """,currentId,previousId,FinancialPeriodComparisonService.hyundaiMotorProfile(2024),evidence);
        var date=LocalDate.of(2025,3,13);
        var previousResult=features.create(company,2023,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(previousResult.analysisAllowed()).isFalse();
        assertThat(previousResult.metrics().revenueGrowth()).isNull();
        var approved=features.create(company,2024,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(approved.comparisonEvidence().previous().receiptNumber()).isEqualTo(previous);
        assertThat(approved.metrics().revenueGrowth()).isEqualByComparingTo("20.0000");
        assertThat(approved.forecastReady()).isFalse();
        jdbc.update("UPDATE financial_period_comparison_review SET evidence=evidence-'previousPeriodExclusionReason'");
        assertThat(features.create(company,2024,ReportType.ANNUAL,StatementType.CFS,date).comparisonEvidence()).isNull();
    }

    @Test void kogasSameDayMainNeedsExactReviewAndDoesNotFallBackAfterLaterCorrection() {
        long id=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00261285','Synthetic KOGAS fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,id,"f".repeat(64));
        String first="20220321001375", attachment="20220325000207", main="20220325000211";
        for(String receipt:java.util.List.of(first,attachment,main)) {
            LocalDate date=receipt.equals(first) ? LocalDate.of(2022,3,21):LocalDate.of(2022,3,25);
            String title=(receipt.equals(first) ? "":receipt.equals(attachment) ? "[첨부정정]":"[기재정정]")+"사업보고서 (2021.12)";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,?,?)
                """,id,receipt,date,title,!receipt.equals(first),raw);
            if(receipt.equals(attachment)) continue;
            byte[] bytes=receipt.getBytes(StandardCharsets.UTF_8);
            String hash=ReportVersionCollectionService.sha256(bytes);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                id,receipt,hash,bytes);
            long version=jdbc.queryForObject("""
                INSERT INTO financial_report_version(company_id,fiscal_year,report_type,statement_type,
                    receipt_number,main_entry,document_encoding,extraction_profile,evidence)
                VALUES (?,2021,'ANNUAL','CFS',?,'synthetic.xml','UTF-8',
                    'KOGAS_2021_ANNUAL_CFS_SIX_TOTALS_V1',CAST('{}' AS jsonb)) RETURNING id
                """,Long.class,id,receipt);
            for(StandardAccount account:StandardAccount.values()) {
                if(account==StandardAccount.UNMAPPED) continue;
                String division=switch(account) {
                    case ASSETS,LIABILITIES,EQUITY -> "BS";
                    default -> "IS";
                };
                long amount=switch(account) {
                    case ASSETS -> 300;
                    case LIABILITIES -> 200;
                    case EQUITY,REVENUE -> 100;
                    case OPERATING_PROFIT -> 10;
                    case NET_INCOME -> 8;
                    default -> throw new IllegalStateException();
                };
                jdbc.update("""
                    INSERT INTO financial_version_fact(version_id,standard_account,raw_account_name,
                        statement_division,amount,currency,display_unit)
                    VALUES (?,?,?,?,?,'KRW',1)
                    """,version,account.name(),account.name(),division,amount);
            }
        }
        var cutoff=LocalDate.of(2022,3,26);
        assertThatThrownBy(() -> queries.find(id,2021,ReportType.ANNUAL,StatementType.CFS,cutoff))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .hasMessageContaining("Multiple filings");
        String hash=ReportVersionCollectionService.sha256(main.getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            INSERT INTO financial_same_day_main_review(company_id,fiscal_year,report_type,statement_type,
                filing_date,selected_main_receipt_number,attachment_receipt_number,selected_main_sha256,
                review_profile,evidence)
            VALUES (?,2021,'ANNUAL','CFS',DATE '2022-03-25',?,?,?,
                'KOGAS_2021_CFS_SAME_DAY_MAIN_ATTACHMENT_SIX_TOTALS_V1',CAST('{"synthetic":true}' AS jsonb))
            """,id,main,attachment,hash);
        var result=queries.find(id,2021,ReportType.ANNUAL,StatementType.CFS,cutoff);
        assertThat(result.receiptNumber()).isEqualTo(main);
        assertThat(result.warnings()).contains("REVIEWED_SAME_DAY_MAIN_ATTACHMENT_SIX_TOTALS_ONLY");
        assertThat(result.accounts()).hasSize(6);
        jdbc.update("UPDATE financial_same_day_main_review SET selected_main_sha256=? WHERE company_id=?","0".repeat(64),id);
        assertThatThrownBy(() -> queries.find(id,2021,ReportType.ANNUAL,StatementType.CFS,cutoff))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
        jdbc.update("UPDATE financial_same_day_main_review SET selected_main_sha256=? WHERE company_id=?",hash,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20220401000001',DATE '2022-04-01','[기재정정]사업보고서 (2021.12)',true,?)
            """,id,raw);
        assertThatThrownBy(() -> queries.find(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,4,2)))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("not been reviewed");
    }

    @Test void preservesBothVersionsAndSelectsByConservativeDateBoundary() throws Exception {
        collector.savePilot(documents); collector.savePilot(documents);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
        assertThat(find("2017-06-01").receiptNumber()).isEqualTo("20170331004820");
        assertThat(find("2022-02-17").receiptNumber()).isEqualTo("20170331004820");
        assertThat(find("2022-02-18").receiptNumber()).isEqualTo("20220217000946");
        assertThatThrownBy(() -> find("2017-03-31")).isInstanceOf(NotFoundException.class);
        mvc.perform(get("/api/companies/{id}/financials/as-of",company).param("fiscalYear","2016").param("asOf","2017-06-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.receiptNumber").value("20170331004820"))
            .andExpect(jsonPath("$.accounts.length()").value(6));
        mvc.perform(get("/api/companies/{id}/financials/as-of",company).param("fiscalYear","2016").param("asOf","bad-date"))
            .andExpect(status().isBadRequest());
    }

    @Test void neverFallsBackPastAnUnreviewedCorrectionOrAmbiguousSameDay() {
        collector.savePilot(documents);
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230101000001',DATE '2023-01-01','[기재정정]사업보고서 (2016.12)',true,?)
            """,company,raw);
        assertThatThrownBy(() -> find("2023-01-02")).isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2022-02-17' WHERE receipt_number='20230101000001'");
        assertThatThrownBy(() -> find("2022-02-18")).isInstanceOf(NotFoundException.class).hasMessageContaining("Multiple filings");
    }

    @Test void steelAttachmentReviewNeedsBothExactReceiptsAndBlocksAnyLaterCorrection() {
        long steel=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00145880','Synthetic Steel') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,steel,"1".repeat(64));
        String original="20260318001506", first="20260319000771", second="20260319000868";
        for(String receipt:java.util.List.of(original,first,second)) {
            boolean main=receipt.equals(original);
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,?,?)
                """,steel,receipt,main ? LocalDate.of(2026,3,18):LocalDate.of(2026,3,19),
                main ? "사업보고서 (2025.12)":"[첨부정정]사업보고서 (2025.12)",!main,raw);
        }
        String hash="a".repeat(64);
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
            steel,original,hash,new byte[]{1});
        long version=jdbc.queryForObject("""
            INSERT INTO financial_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
              main_entry,document_encoding,extraction_profile,evidence)
            VALUES (?,2025,'ANNUAL','CFS',?,'synthetic.xml','UTF-8','STEEL_2025_ANNUAL_CFS_SIX_TOTALS_V1',CAST('{}' AS jsonb)) RETURNING id
            """,Long.class,steel,original);
        for(var account:java.util.List.of(StandardAccount.REVENUE,StandardAccount.OPERATING_PROFIT,
                StandardAccount.NET_INCOME,StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY)) jdbc.update("""
            INSERT INTO financial_version_fact(version_id,standard_account,raw_account_name,statement_division,
              amount,currency,display_unit) VALUES (?,?,?,? ,100,'KRW',1)
            """,version,account.name(),account.name(),
            java.util.Set.of(StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY).contains(account) ? "BS":"IS");
        var asOf=LocalDate.of(2026,3,20);
        assertThatThrownBy(() -> queries.find(steel,2025,ReportType.ANNUAL,StatementType.CFS,asOf))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("Multiple filings");
        insertSyntheticAttachmentReview(steel,first,hash);
        assertThatThrownBy(() -> queries.find(steel,2025,ReportType.ANNUAL,StatementType.CFS,asOf))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("Multiple filings");
        insertSyntheticAttachmentReview(steel,second,hash);
        var allowed=queries.find(steel,2025,ReportType.ANNUAL,StatementType.CFS,asOf);
        assertThat(allowed.receiptNumber()).isEqualTo(original);
        assertThat(allowed.accounts()).hasSize(6);
        assertThat(allowed.warnings()).contains("REVIEWED_ATTACHMENT_CORRECTIONS_ORIGINAL_MAIN_SIX_TOTALS_UNCHANGED");
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20260402000001',DATE '2026-04-02','[기재정정]사업보고서 (2025.12)',true,?)
            """,steel,raw);
        assertThatThrownBy(() -> queries.find(steel,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,3)))
            .isInstanceOf(FinancialAsOfUnavailableException.class).hasMessageContaining("no older-version fallback");
    }

    private void insertSyntheticAttachmentReview(long steel,String receipt,String hash) {
        jdbc.update("""
            INSERT INTO financial_attachment_correction_review(company_id,fiscal_year,report_type,statement_type,
              original_receipt_number,correction_receipt_number,correction_date,original_document_sha256,review_profile,evidence)
            VALUES (?,2025,'ANNUAL','CFS','20260318001506',?,DATE '2026-03-19',?,
              'STEEL_2025_CFS_ATTACHMENT_ONLY_V1',CAST('{"synthetic":true}' AS jsonb))
            """,steel,receipt,hash);
    }

    @Test void rejectsUncoveredDatesAndUnreviewedStatementScope() {
        collector.savePilot(documents);
        assertThatThrownBy(() -> find("2026-10-15")).isInstanceOf(NotFoundException.class).hasMessageContaining("Refresh");
        assertThatThrownBy(() -> queries.find(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.of(2017,6,1)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test void changedSourceArchiveRollsBackAndPreservesOriginalEvidence() throws Exception {
        collector.savePilot(documents);
        String before=jdbc.queryForObject("SELECT sha256 FROM report_document WHERE receipt_number='20220217000946'",String.class);
        var changed=new java.util.HashMap<>(documents);
        changed.put("20220217000946",Hyundai2016ReportParserTest.zip("20220217000946",
                Hyundai2016ReportParserTest.fixture()+"<!-- Changed archive -->",StandardCharsets.UTF_8));
        assertThatThrownBy(() -> collector.savePilot(changed)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZIP changed");
        assertThat(jdbc.queryForObject("SELECT sha256 FROM report_document WHERE receipt_number='20220217000946'",String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
    }

    @Test void missingSecondPublicationRollsBackTheFirstVersionToo() {
        jdbc.update("DELETE FROM report_publication WHERE receipt_number='20220217000946'");
        assertThatThrownBy(() -> collector.savePilot(documents)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "2017-04-01,20170331004820,1",
        "2022-02-16,20170331004820,1",
        "2022-02-17,20170331004820,1",
        "2022-02-18,20220217000946,2"
    })
    void returnsOnlyTheSixAmountsPublishedBeforeTheRequestedDay(String date,String receipt,int multiplier) throws Exception {
        saveDistinctVersions();
        var result=find(date);
        assertThat(result.receiptNumber()).isEqualTo(receipt);
        assertAmounts(result,multiplier);
        // Check the serialized HTTP result too, not just the internal selected receipt.
        var http=mvc.perform(get("/api/companies/{id}/financials/as-of",company)
                .param("fiscalYear","2016").param("asOf",date))
            .andExpect(status().isOk())
            .andReturn().getResponse();
        var serialized=mapper.readValue(http.getContentAsByteArray(),FinancialAsOfResponse.class);
        assertThat(serialized.receiptNumber()).isEqualTo(receipt);
        assertAmounts(serialized,multiplier);
    }

    @Test void changingFutureValuesCannotChangeAnyPastAccount() throws Exception {
        saveDistinctVersions();
        var before=find("2018-01-01");
        // Deliberately perturb only the future correction in this disposable test DB.
        jdbc.update("""
            UPDATE financial_version_fact SET amount=amount*100
            WHERE version_id=(SELECT id FROM financial_report_version WHERE receipt_number='20220217000946')
            """);
        assertThat(find("2018-01-01")).isEqualTo(before);
        assertAmounts(find("2022-02-18"),200);
    }

    @ParameterizedTest
    @CsvSource({"20170331004820,2018-01-01", "20220217000946,2022-02-18"})
    void incompleteVersionCannotBorrowAnAccountFromAnotherVersion(String receipt,String date) throws Exception {
        saveDistinctVersions();
        jdbc.update("""
            DELETE FROM financial_version_fact WHERE standard_account='REVENUE'
            AND version_id=(SELECT id FROM financial_report_version WHERE receipt_number=?)
            """,receipt);
        assertThatThrownBy(() -> find(date)).isInstanceOf(NotFoundException.class).hasMessageContaining("incomplete");
        mvc.perform(get("/api/companies/{id}/financials/as-of",company)
                .param("fiscalYear","2016").param("asOf",date)).andExpect(status().isNotFound());
    }

    @Test void missingOriginalCannotBeReplacedWithTheFutureCorrection() throws Exception {
        saveDistinctVersions();
        jdbc.update("""
            DELETE FROM financial_version_fact WHERE version_id=
            (SELECT id FROM financial_report_version WHERE receipt_number='20170331004820')
            """);
        jdbc.update("DELETE FROM financial_report_version WHERE receipt_number='20170331004820'");
        assertThatThrownBy(() -> find("2018-01-01")).isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
        assertAmounts(find("2022-02-18"),2);
    }

    @Test void futureUnreviewedFilingBlocksOnlyAfterItsPublicationDay() throws Exception {
        saveDistinctVersions();
        var before=find("2018-01-01");
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230101000001',DATE '2023-01-01','[기재정정]사업보고서 (2016.12)',true,?)
            """,company,raw);
        assertThat(find("2018-01-01")).isEqualTo(before);
        assertAmounts(find("2023-01-01"),2);
        assertThatThrownBy(() -> find("2023-01-02")).isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
    }

    @Test void officialDateControlsAvailabilityNotReceiptPrefixOrCollectionTime() throws Exception {
        saveDistinctVersions();
        // Intentionally inconsistent synthetic metadata makes use of the receipt prefix detectable.
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2022-03-10' WHERE receipt_number='20220217000946'");
        jdbc.update("UPDATE report_document SET downloaded_at=TIMESTAMPTZ '2040-01-01 00:00:00+09'");
        jdbc.update("UPDATE financial_report_version SET reviewed_at=TIMESTAMPTZ '2040-01-01 00:00:00+09'");
        assertAmounts(find("2022-02-18"),1);
        assertAmounts(find("2022-03-10"),1);
        var available=find("2022-03-11");
        assertAmounts(available,2);
        assertThat(available.availableFromKstDate()).isEqualTo(LocalDate.of(2022,3,11));
    }

    @Test void otherFiscalYearsCannotReplaceOrBlockTheRequestedYear() throws Exception {
        saveDistinctVersions();
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230331000001',DATE '2023-03-31','사업보고서 (2022.12)',false,?)
            """,company,raw);
        assertAmounts(find("2023-04-01"),2);
        assertThatThrownBy(() -> queries.find(company,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,1)))
            .isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
    }

    @ParameterizedTest @ValueSource(strings={"2016-12-31","2017-03-30","2017-03-31"})
    void completedFiscalYearDoesNotMeanTheReportWasPublic(String date) throws Exception {
        saveDistinctVersions();
        assertThatThrownBy(() -> find(date)).isInstanceOf(NotFoundException.class).hasMessageContaining("No annual filing");
    }

    @Test void anotherCompanyCannotBlockOrBorrowTheReviewedCompanyValues() throws Exception {
        saveDistinctVersions();
        Long other=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('99999999','Other synthetic company') RETURNING id",Long.class);
        Long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,other,"1".repeat(64));
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230331000002',DATE '2023-03-31','[기재정정]사업보고서 (2016.12)',true,?)
            """,other,raw);
        assertAmounts(find("2023-04-01"),2);
        assertThatThrownBy(() -> queries.find(other,2016,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,1)))
            .isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
    }

    @Test void coverageAllowsOnlyThroughTheStartOfTheFollowingDay() throws Exception {
        saveDistinctVersions();
        assertAmounts(find("2026-10-14"),2);
        assertThatThrownBy(() -> find("2026-10-15")).isInstanceOf(NotFoundException.class).hasMessageContaining("Refresh");
    }

    private void saveDistinctVersions() throws Exception {
        String original=Hyundai2016ReportParserTest.fixture()
            .replace("178,835,928","1,000").replace("106,491,350","600").replace("72,344,578","400")
            .replace("93,649,024","100").replace("5,193,500","10").replace("5,719,653","5");
        String correction=Hyundai2016ReportParserTest.fixture()
            .replace("178,835,928","2,000").replace("106,491,350","1,200").replace("72,344,578","800")
            .replace("93,649,024","200").replace("5,193,500","20").replace("5,719,653","10");
        collector.savePilot(Map.of(
            "20170331004820",Hyundai2016ReportParserTest.zip("20170331004820",original,StandardCharsets.UTF_8),
            "20220217000946",Hyundai2016ReportParserTest.zip("20220217000946",correction,StandardCharsets.UTF_8)));
    }

    private static void assertAmounts(FinancialAsOfResponse response,int multiplier) {
        // Independent expected values in KRW; never derive expected results with the production parser/query.
        Map<StandardAccount,String> expected=Map.of(
            StandardAccount.REVENUE,"100000000",StandardAccount.OPERATING_PROFIT,"10000000",
            StandardAccount.NET_INCOME,"5000000",StandardAccount.ASSETS,"1000000000",
            StandardAccount.LIABILITIES,"600000000",StandardAccount.EQUITY,"400000000");
        assertThat(response.accounts()).extracting(FinancialAsOfResponse.Account::standardAccount)
            .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (var account:response.accounts()) {
            assertThat(account.amount()).as("%s at %s",account.standardAccount(),response.asOf())
                .isEqualByComparingTo(new BigDecimal(expected.get(account.standardAccount())).multiply(BigDecimal.valueOf(multiplier)));
        }
    }

    @ParameterizedTest
    @CsvSource({"2017-04-01,false", "2022-02-16,false", "2022-02-17,false", "2022-02-18,true"})
    void reviewedRealSeparateAmountsFollowPublicationBoundaryAndStaySeparateFromCfs(String date,boolean corrected) throws Exception {
        var mixed=documentsWithSeparateExcerpts();
        collector.savePilot(mixed);
        collector.savePilot(mixed,StatementType.OFS);
        collector.savePilot(mixed,StatementType.OFS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(24);
        var response=queries.find(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.parse(date));
        assertThat(response.receiptNumber()).isEqualTo(corrected ? "20220217000946":"20170331004820");
        assertThat(response.statementType()).isEqualTo(StatementType.OFS);
        assertThat(response.extractionProfile()).isEqualTo(Hyundai2016ReportParser.OFS_PROFILE);
        var expected=Map.of(StandardAccount.ASSETS,corrected ? "69737669000000":"69850917000000",
            StandardAccount.LIABILITIES,corrected ? "17451676000000":"17271206000000",
            StandardAccount.EQUITY,corrected ? "52285993000000":"52579711000000",
            StandardAccount.REVENUE,"41713632000000",StandardAccount.OPERATING_PROFIT,"2699482000000",StandardAccount.NET_INCOME,"4101860000000");
        assertThat(response.accounts()).hasSize(6);
        for(var account:response.accounts()) assertThat(account.amount()).as("%s %s",date,account.standardAccount())
            .isEqualByComparingTo(expected.get(account.standardAccount()));
        var cfsAssets=find(date).accounts().stream().filter(a -> a.standardAccount()==StandardAccount.ASSETS).findFirst().orElseThrow();
        assertThat(cfsAssets.amount()).isEqualByComparingTo("178835928000000");
        var http=mvc.perform(get("/api/companies/{id}/financials/as-of",company).param("fiscalYear","2016")
            .param("asOf",date).param("statementType","OFS")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readValue(http.getContentAsByteArray(),FinancialAsOfResponse.class)).isEqualTo(response);
    }

    @Test void missingSeparateCorrectionDoesNotFallBackToOriginalOrConsolidatedCorrection() throws Exception {
        var mixed=documentsWithSeparateExcerpts();
        collector.savePilot(mixed); collector.savePilot(mixed,StatementType.OFS);
        jdbc.update("""
            DELETE FROM financial_version_fact WHERE version_id=(SELECT id FROM financial_report_version
            WHERE statement_type='OFS' AND receipt_number='20220217000946')
            """);
        jdbc.update("DELETE FROM financial_report_version WHERE statement_type='OFS' AND receipt_number='20220217000946'");
        assertThatThrownBy(() -> queries.find(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.of(2022,2,18)))
            .isInstanceOf(NotFoundException.class).hasMessageContaining("no older-version fallback");
        assertThat(find("2022-02-18").accounts()).hasSize(6);
        assertThat(queries.find(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.of(2022,2,17)).receiptNumber())
            .isEqualTo("20170331004820");
    }

    @Test void failedSeparateCollectionKeepsExistingConsolidatedVersionsUntouched() throws Exception {
        collector.savePilot(documents);
        var before=find("2018-01-01");
        // Original scope is valid but corrected scope is missing. No partial OFS import is allowed.
        var mixed=new java.util.HashMap<>(documentsWithSeparateExcerpts());
        mixed.put("20220217000946",documents.get("20220217000946"));
        assertThatThrownBy(() -> collector.savePilot(mixed,StatementType.OFS))
            .isInstanceOf(io.corpscope.ingestion.DataQualityException.class);
        assertThat(find("2018-01-01")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
    }

    private Map<String,byte[]> documentsWithSeparateExcerpts() throws Exception {
        var result=new java.util.HashMap<String,byte[]>();
        for(String receipt:ReportVersionCollectionService.RECEIPTS) {
            String separate=Hyundai2016ReportParserTest.ofsFixture(receipt.equals("20220217000946"));
            String mixed=Hyundai2016ReportParserTest.fixture().replace("</DOCUMENT>",
                separate.substring(separate.indexOf("<TABLE-GROUP"),separate.indexOf("</DOCUMENT>"))+"</DOCUMENT>");
            result.put(receipt,Hyundai2016ReportParserTest.zip(receipt,mixed,StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test void actualSeparateRestatementChangesDebtFeatureOnlyWhenPublic() throws Exception {
        collector.savePilot(documentsWithSeparateExcerpts(),StatementType.OFS);
        var before=features.create(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.of(2022,2,17));
        var after=features.create(company,2016,ReportType.ANNUAL,StatementType.OFS,LocalDate.of(2022,2,18));
        assertThat(before.metrics().debtRatio()).isEqualByComparingTo("32.8477");
        assertThat(after.metrics().debtRatio()).isEqualByComparingTo("33.3773");
        assertThat(after.metrics().operatingMargin()).isEqualByComparingTo(before.metrics().operatingMargin());
        assertThat(before.values().get(StandardAccount.ASSETS)).isEqualByComparingTo("69850917000000");
        assertThat(after.values().get(StandardAccount.ASSETS)).isEqualByComparingTo("69737669000000");
        assertThat(after.metrics().roa()).isNull();
        assertThat(after.forecastReady()).isFalse();
        mvc.perform(get("/api/companies/{id}/financials/as-of/features",company).param("fiscalYear","2016")
            .param("statementType","OFS").param("asOf","2022-02-17"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.values.ASSETS").value(69850917000000L))
            .andExpect(jsonPath("$.evidence.receiptNumber").value("20170331004820"))
            .andExpect(jsonPath("$.forecastReady").value(false))
            .andExpect(jsonPath("$.knownFilingsCheckedThrough").doesNotExist())
            .andExpect(jsonPath("$.metrics.revenueGrowth").isEmpty())
            .andExpect(jsonPath("$.unavailableMetricReasons.roa").value("PREVIOUS_PERIOD_BASIS_NOT_VERIFIED"));
    }

    @Test void futureValuesCannotAffectPastFeaturesOrExportRows() throws Exception {
        saveDistinctVersions();
        var dates=java.util.List.of(LocalDate.of(2018,1,1),LocalDate.of(2019,1,1));
        var before=exports.export("00164742",2016,StatementType.CFS,dates);
        jdbc.update("""
            UPDATE financial_version_fact SET amount=amount*100 WHERE version_id=
            (SELECT id FROM financial_report_version WHERE receipt_number='20220217000946')
            """);
        var after=exports.export("00164742",2016,StatementType.CFS,dates);
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(after.exportedRows()).isEqualTo(2);
        assertThat(after.withheld()).isEmpty();
        assertThat(features.create(company,2016,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,2,18))
            .values().get(StandardAccount.REVENUE)).isEqualByComparingTo("20000000000");
    }

    @Test void exportSortsDatesAndExplicitlyAccountsForEveryWithheldRequest() throws Exception {
        saveDistinctVersions();
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230101000001',DATE '2023-01-01','[기재정정]사업보고서 (2016.12)',true,?)
            """,company,raw);
        var result=exports.export("00164742",2016,StatementType.CFS,java.util.List.of(
            LocalDate.of(2023,1,2),LocalDate.of(2017,4,1),LocalDate.of(2017,3,31)));
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.requestedRows()).isEqualTo(result.exportedRows()+result.withheldRows()).isEqualTo(3);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.withheld()).extracting(FinancialFeatureExport.Withheld::reason)
            .containsExactly("NO_PUBLIC_FILING","UNREVIEWED_SELECTED_FILING");
        mvc.perform(get("/api/companies/{id}/financials/as-of/features",company).param("fiscalYear","2016").param("asOf","2023-01-02"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.availabilityReason").value("UNREVIEWED_SELECTED_FILING"));
    }

    @Test void analysisExcludedRowsAreWithheldFromExportRatherThanZeroFilled() {
        collector.savePilot(documents);
        var guardedFeatures=new FinancialFeatureService(queries,new io.corpscope.analysis.FinancialMetricsCalculator(),
            new io.corpscope.analysis.AnalysisEligibilityProperties(Map.of(),Map.of("00164742:2016:ANNUAL:CFS","Synthetic period review hold")),comparisons);
        var guardedExport=new FinancialFeatureExportService(jdbc,guardedFeatures);
        var result=guardedExport.export("00164742",2016,StatementType.CFS,java.util.List.of(LocalDate.of(2018,1,1)));
        assertThat(result.status()).isEqualTo("WITHHELD");
        assertThat(result.rows()).isEmpty();
        assertThat(result.withheld().getFirst().reason()).isEqualTo("ANALYSIS_EXCLUDED");
        assertThat(find("2018-01-01").accounts()).hasSize(6);
    }

    @Test void inventoryReportsReviewedScopesAndMakesTruncationExplicit() throws Exception {
        var mixed=documentsWithSeparateExcerpts();
        collector.savePilot(mixed); collector.savePilot(mixed,StatementType.OFS);
        var complete=coverage.review(LocalDate.of(2018,1,1),10,"");
        assertThat(complete.totalScopes()).isEqualTo(2);
        assertThat(complete.returnedStatusCounts()).containsEntry("REVIEWED_VALUES_AVAILABLE",2L);
        assertThat(complete.truncated()).isFalse();
        assertThat(complete.forecastReady()).isFalse();
        var partial=coverage.review(LocalDate.of(2018,1,1),1,"00164742");
        assertThat(partial.returnedScopes()).isEqualTo(1);
        assertThat(partial.totalScopes()).isEqualTo(2);
        assertThat(partial.truncated()).isTrue();
        assertThat(partial.returnedStatusCounts().values()).containsExactly(1L);
    }

    @Test void latestStoredFactsDoNotBecomeHistoricallyReviewedAndDemoScopesAreExcluded() {
        collector.savePilot(documents);
        insertLatestFact(2017,"OPENDART"); insertLatestFact(2018,"DEMO");
        Long raw=jdbc.queryForObject("SELECT min(id) FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20180331000001',DATE '2018-03-31','사업보고서 (2017.12)',false,?)
            """,company,raw);
        var result=coverage.review(LocalDate.of(2018,4,1),10,"");
        assertThat(result.totalScopes()).isEqualTo(2);
        assertThat(result.returnedStatusCounts()).containsEntry("REVIEWED_VALUES_AVAILABLE",1L).containsEntry("UNREVIEWED_SELECTED_FILING",1L);
        assertThat(result.scopes()).extracting(HistoricalCoverageReport.Scope::fiscalYear).containsExactly(2016,2017);
        assertThat(exports.export("00164742",2017,StatementType.CFS,java.util.List.of(LocalDate.of(2018,4,1))).rows()).isEmpty();
    }

    @Test void inventoryUsesTheSameOfficialCoverageBoundaryAsFeatures() {
        collector.savePilot(documents);
        assertThat(coverage.review(LocalDate.of(2026,10,15),10,"").returnedStatusCounts())
            .containsEntry("OUTSIDE_FILING_COVERAGE",1L);
        jdbc.update("UPDATE raw_ingestion SET endpoint='unverified-list'");
        assertThat(coverage.review(LocalDate.of(2018,1,1),10,"").returnedStatusCounts())
            .containsEntry("NO_FILING_COVERAGE",1L);
    }

    @Test void reviewPlanListsOnlyCandidatesPublicAtTheRequestedDateAndDoesNotInferSameDayOrder() {
        collector.savePilot(documents);
        var before=coverage.review(LocalDate.of(2022,2,17),10,"").scopes().getFirst();
        assertThat(before.filingCandidates()).extracting(HistoricalCoverageReport.FilingCandidate::receiptNumber)
            .containsExactly("20170331004820");
        assertThat(before.nextActions()).containsExactly("VERIFY_CROSS_PERIOD_BASIS");
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20220217000001',DATE '2022-02-17','[기재정정]사업보고서 (2016.12)',true,?)
            """,company,raw);
        var ambiguous=coverage.review(LocalDate.of(2022,2,18),10,"").scopes().getFirst();
        assertThat(ambiguous.availabilityStatus()).isEqualTo("AMBIGUOUS_PUBLICATION_DAY");
        assertThat(ambiguous.receiptNumber()).isNull();
        assertThat(ambiguous.filingCandidates()).hasSize(3);
        assertThat(ambiguous.nextActions()).containsExactly("REVIEW_PUBLICATION_ORDER");
        assertThat(ambiguous.filingCandidates()).filteredOn(c -> c.receiptNumber().equals("20220217000001"))
            .allSatisfy(c -> {assertThat(c.documentStored()).isFalse();assertThat(c.sixTotalsReviewed()).isFalse();});
    }

    @Test void reviewPlanPreservesCurrentHoldEvenWhenHistoricalValuesAreNotReviewed() {
        collector.savePilot(documents);
        var policy=new io.corpscope.analysis.AnalysisEligibilityProperties(Map.of("00164742","Current review hold"),Map.of());
        var review=new HistoricalCoverageService(jdbc,features,policy);
        jdbc.update("DELETE FROM financial_version_fact");
        var result=review.review(LocalDate.of(2018,1,1),10,"").scopes().getFirst();
        assertThat(result.availabilityStatus()).isEqualTo("INCOMPLETE_REVIEWED_VALUES");
        assertThat(result.analysisAllowed()).isNull();
        assertThat(result.exclusionReason()).isEqualTo("Current review hold");
        assertThat(result.nextActions()).containsExactly("REVIEW_INCOMPLETE_VERSION","RESOLVE_ANALYSIS_HOLD");
    }

    @Test void candidateListTruncationDoesNotPretendToBeACompleteCorrectionChain() {
        collector.savePilot(documents);
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        for(int n=0;n<21;n++) jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,?,?,'[기재정정]사업보고서 (2016.12)',true,?)
            """,company,"202301"+String.format("%08d",n),LocalDate.of(2023,1,1).plusDays(n),raw);
        var scope=coverage.review(LocalDate.of(2023,2,1),10,"").scopes().getFirst();
        assertThat(scope.candidatesTruncated()).isTrue();
        assertThat(scope.filingCandidates()).hasSize(20);
        assertThat(scope.availabilityStatus()).isEqualTo("UNREVIEWED_SELECTED_FILING");
    }

    @Test void rawDocumentCollectionIsIdempotentAndDoesNotApproveFinancialValues() {
        DartGateway gateway=documentGateway();
        for(String receipt:ReportVersionCollectionService.RECEIPTS) when(gateway.document(receipt)).thenReturn(documents.get(receipt));
        var service=new ReportDocumentCollectionService(gateway,jdbc,transactions);
        var first=service.collect("00164742",ReportVersionCollectionService.RECEIPTS);
        var second=service.collect("00164742",ReportVersionCollectionService.RECEIPTS);
        assertThat(first).allSatisfy(d -> {assertThat(d.alreadyStored()).isFalse();assertThat(d.financialReviewPerformed()).isFalse();});
        assertThat(second).allSatisfy(d -> assertThat(d.alreadyStored()).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        insertLatestFact(2016,"OPENDART");
        var scope=coverage.review(LocalDate.of(2022,2,18),10,"").scopes().getFirst();
        assertThat(scope.availabilityStatus()).isEqualTo("UNREVIEWED_SELECTED_FILING");
        assertThat(scope.filingCandidates()).allSatisfy(c -> {assertThat(c.documentStored()).isTrue();assertThat(c.sixTotalsReviewed()).isFalse();});
    }

    @Test void changedArchiveRollsBackEarlierNewArchiveAndPreservesReviewedVersions() throws Exception {
        collector.savePilot(documents);
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion",Long.class);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230101000001',DATE '2023-01-01','[ 기재정정 ]사업보고서 (2016.12)',true,?)
            """,company,raw);
        DartGateway gateway=documentGateway();
        when(gateway.document("20230101000001")).thenReturn(Hyundai2016ReportParserTest.zip("20230101000001",Hyundai2016ReportParserTest.fixture(),StandardCharsets.UTF_8));
        when(gateway.document("20220217000946")).thenReturn(Hyundai2016ReportParserTest.zip("20220217000946",Hyundai2016ReportParserTest.fixture()+"<!-- changed -->",StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new ReportDocumentCollectionService(gateway,jdbc,transactions)
            .collect("00164742",java.util.List.of("20230101000001","20220217000946")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZIP changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(find("2018-01-01").accounts()).hasSize(6);
    }

    @Test void unverifiedReceiptIsRejectedBeforeAnyDocumentDownload() {
        DartGateway gateway=documentGateway();
        assertThatThrownBy(() -> new ReportDocumentCollectionService(gateway,jdbc,transactions)
            .collect("00164742",java.util.List.of("20240101000001")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified periodic publication metadata");
        verify(gateway,never()).document(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isZero();
    }

    @Test void badArchiveResponseDoesNotWriteAnyDocument() {
        DartGateway gateway=documentGateway();
        when(gateway.document("20170331004820")).thenReturn("<error>not a zip</error>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new ReportDocumentCollectionService(gateway,jdbc,transactions)
            .collect("00164742",java.util.List.of("20170331004820")))
            .isInstanceOf(io.corpscope.external.dart.DartException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isZero();
    }

    @Test void secondDownloadFailureLeavesNoPartialRawArchiveImport() {
        DartGateway gateway=documentGateway();
        when(gateway.document("20170331004820")).thenReturn(documents.get("20170331004820"));
        when(gateway.document("20220217000946")).thenThrow(new io.corpscope.external.dart.DartException.Transient("Synthetic timeout"));
        assertThatThrownBy(() -> new ReportDocumentCollectionService(gateway,jdbc,transactions)
            .collect("00164742",ReportVersionCollectionService.RECEIPTS))
            .isInstanceOf(io.corpscope.external.dart.DartException.Transient.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isZero();
    }

    private DartGateway documentGateway() {
        var gateway=mock(DartGateway.class);
        when(gateway.source()).thenReturn("OPENDART");
        return gateway;
    }

    @ParameterizedTest @CsvSource({"2024,2025-03-12,300870903000000", "2025,2026-03-11,333605938000000"})
    void samsungReviewedVersionsAreIdempotentAndSelectTheirOwnFiscalPeriod(int year,String date,String revenue) throws Exception {
        long samsung=prepareSamsung(false);
        assertThat(samsungReviews.review()).containsEntry("sixComparativesMatch",true).containsEntry("crossPeriodMetricsApproved",true);
        samsungReviews.review();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
        var result=queries.find(samsung,year,ReportType.ANNUAL,StatementType.CFS,LocalDate.parse(date));
        assertThat(result.accounts()).filteredOn(a -> a.standardAccount()==StandardAccount.REVENUE)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo(revenue));
        assertThat(coverage.review(LocalDate.of(2026,10,13),10,"00126380").returnedStatusCounts()).containsEntry("REVIEWED_VALUES_AVAILABLE",2L);
        var before=LocalDate.parse(date).minusDays(1);
        assertThatThrownBy(() -> queries.find(samsung,year,ReportType.ANNUAL,StatementType.CFS,before))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void invalidSecondSamsungPublicationRollsBackFirstReviewedVersion() throws Exception {
        prepareSamsung(false);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2026-03-11' WHERE receipt_number='20260310002820'");
        assertThatThrownBy(() -> samsungReviews.review()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Official receipt date");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_document",Integer.class)).isEqualTo(2);
    }

    @Test void comparativeMismatchDoesNotInventApprovalOrReplaceOriginalAmounts() throws Exception {
        long samsung=prepareSamsung(true);
        assertThat(samsungReviews.review()).containsEntry("sixComparativesMatch",false).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11)).metrics().revenueGrowth()).isNull();
        var before=queries.find(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,12));
        assertThat(before.accounts()).filteredOn(a -> a.standardAccount()==StandardAccount.REVENUE)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("300870903000000"));
    }

    @Test void storedReviewEvidenceChangeRequiresExplicitReviewInsteadOfSilentUpsert() throws Exception {
        prepareSamsung(false); samsungReviews.review();
        jdbc.update("UPDATE financial_report_version SET evidence=evidence||'{\"tampered\":true}'::jsonb WHERE fiscal_year=2025");
        assertThatThrownBy(() -> samsungReviews.review()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Reviewed extraction changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
    }

    private long prepareSamsungHistory() throws Exception {
        long samsung=prepareSamsung(false);
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,samsung);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20240312000736',DATE '2024-03-12','사업보고서 (2023.12)',false,?)
            """,samsung,raw);
        byte[] zip=Hyundai2016ReportParserTest.zip("20240312000736",SamsungAnnualReportParserTest.fixture(2023),StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,'20240312000736',?,?)",
            samsung,ReportVersionCollectionService.sha256(zip),zip);
        return samsung;
    }

    @Test void earlierSamsungPairIsIdempotentAndKeepsUnreviewed2022MetricsNull() throws Exception {
        long samsung=prepareSamsungHistory();
        samsungReviews.review();
        assertThat(samsungReviews.review(2024)).containsEntry("sixComparativesMatch",true);
        samsungReviews.review(2024);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        var previous=features.create(samsung,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,13));
        assertThat(previous.metrics().revenueGrowth()).isNull();
        assertThat(previous.metrics().roa()).isNull();
        assertThat(previous.metrics().roe()).isNull();
        assertThat(previous.metrics().operatingMargin()).isNotNull();
        var current=features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,12));
        assertThat(current.comparisonEvidence().comparisonProfile()).isEqualTo("SAMSUNG_2023_2024_CFS_SIX_TOTALS_V1");
        assertThat(current.comparisonEvidence().previous().receiptNumber()).isEqualTo("20240312000736");
        assertThat(current.comparisonEvidence().evidenceAvailableFromKstDate()).isEqualTo(LocalDate.of(2025,3,12));
        assertThat(current.metrics().revenueGrowth()).isEqualByComparingTo("16.1953");
        assertThat(current.metrics().operatingMargin()).isEqualByComparingTo("10.8771");
        assertThat(current.metrics().debtRatio()).isEqualByComparingTo("27.9319");
        assertThat(current.metrics().roa()).isEqualByComparingTo("7.1002");
        assertThat(current.metrics().roe()).isEqualByComparingTo("8.9967");
        assertThatThrownBy(() -> features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,11)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void future2025ValuesCannotChange2024MetricsOrTheirPreviousEvidence() throws Exception {
        long samsung=prepareSamsungHistory();
        samsungReviews.review(2024); samsungReviews.review();
        var before=features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,12));
        jdbc.update("UPDATE financial_version_fact SET amount=amount+999999 WHERE version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2025)");
        var after=features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,12));
        assertThat(after).isEqualTo(before);
    }

    @Test void earlierPairRollsBackNew2023VersionWhenExisting2024EvidenceChanges() throws Exception {
        prepareSamsungHistory(); samsungReviews.review();
        jdbc.update("UPDATE financial_report_version SET evidence=evidence||'{\"tampered\":true}'::jsonb WHERE fiscal_year=2024");
        assertThatThrownBy(() -> samsungReviews.review(2024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2023",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
    }

    @Test void unreviewed2023CorrectionStops2024CrossMetricsWithoutAnOlderFallback() throws Exception {
        long samsung=prepareSamsungHistory(); samsungReviews.review(2024);
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,samsung);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250401000001',DATE '2025-04-01','[기재정정]사업보고서 (2023.12)',true,?)
            """,samsung,raw);
        assertThat(features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,1)).metrics().roa()).isNotNull();
        var after=features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,2));
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().roa()).isNull();
        assertThat(after.metrics().roe()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    @Test void changed2023TotalCannotReuse2024ApprovalOrComparativeColumn() throws Exception {
        long samsung=prepareSamsungHistory(); samsungReviews.review(2024);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        var result=features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,12));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.metrics().debtRatio()).isNotNull();
    }

    @Test void unsupportedYearCannotCreateAnImplicitComparisonApproval() throws Exception {
        prepareSamsungHistory();
        assertThatThrownBy(() -> samsungReviews.review(2020)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
    }

    private long prepareSamsungFiveYears() throws Exception {
        long samsung=prepareSamsungHistory();
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,samsung);
        for(int year:java.util.List.of(2021,2022)) {
            String receipt=year==2021 ? "20220308000798":"20230307000542";
            LocalDate date=year==2021 ? LocalDate.of(2022,3,8):LocalDate.of(2023,3,7);
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,false,?)
                """,samsung,receipt,date,"사업보고서 ("+year+".12)",raw);
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,SamsungAnnualReportParserTest.fixture(year),StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                samsung,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return samsung;
    }

    @Test void fiveYearsUseOnlyApprovedConsecutiveVersionsAndPreserveOldestHold() throws Exception {
        long samsung=prepareSamsungFiveYears();
        for(int year:java.util.List.of(2023,2022,2025,2024)) {samsungReviews.review(year);samsungReviews.review(year);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(4);
        var oldest=features.create(samsung,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,9));
        assertThat(oldest.comparisonEvidence()).isNull();
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        for(int year:java.util.List.of(2022,2023)) {
            var date=year==2022 ? LocalDate.of(2023,3,8):LocalDate.of(2024,3,13);
            var result=features.create(samsung,year,ReportType.ANNUAL,StatementType.CFS,date);
            assertThat(result.comparisonEvidence().previousFiscalYear()).isEqualTo(year-1);
            assertThat(result.comparisonEvidence().evidenceAvailableFromKstDate()).isEqualTo(date);
            assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo(year==2022 ? "8.0923":"-14.3254");
            assertThat(result.metrics().roa()).isNotNull();
            assertThat(result.metrics().roe()).isNotNull();
            assertThatThrownBy(() -> features.create(samsung,year,ReportType.ANNUAL,StatementType.CFS,date.minusDays(1)))
                .isInstanceOf(FinancialAsOfUnavailableException.class);
        }
    }

    @ParameterizedTest @ValueSource(ints={2022,2023})
    void missingOldPreviousFactCannotBePatchedFromNewerComparativeColumn(int year) throws Exception {
        long samsung=prepareSamsungFiveYears();samsungReviews.review(year);
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=?)",year-1);
        var result=features.create(samsung,year,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    private long prepareSamsung2020(boolean mismatch) throws Exception {
        long id=prepareSamsungFiveYears();
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20210309000744',DATE '2021-03-09','사업보고서 (2020.12)',false,?)
            """,id,raw);
        String xml=SamsungAnnualReportParserTest.fixture(2020);
        if(mismatch) xml=xml.replace("236,806,988","236,806,987");
        byte[] zip=Hyundai2016ReportParserTest.zip("20210309000744",xml,java.nio.charset.Charset.forName("MS949"));
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,'20210309000744',?,?)",
            id,ReportVersionCollectionService.sha256(zip),zip);
        return id;
    }

    @Test void samsung2020AddsExact2021PairAndKeepsAllLaterPairs() throws Exception {
        long id=prepareSamsung2020(false);
        for(int year:java.util.List.of(2022,2023,2024,2025)) samsungReviews.review(year);
        var later=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        samsungReviews.review(2021);samsungReviews.review(2021);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(5);
        var result=features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,9));
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20210309000744");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("18.0729");
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("18.4667");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("39.9217");
        assertThat(result.metrics().roa()).isEqualByComparingTo("9.9167");
        assertThat(result.metrics().roe()).isEqualByComparingTo("13.7411");
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11))).isEqualTo(later);
        var oldest=features.create(id,2020,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2021,3,10));
        assertThat(oldest.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("236806988000000");
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThatThrownBy(() -> features.create(id,2020,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2021,3,9)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void samsung2020MismatchDoesNotBorrowApprovalFromLaterPair() throws Exception {
        long id=prepareSamsung2020(true);samsungReviews.review(2022);
        assertThat(samsungReviews.review(2021)).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,9)).comparisonEvidence()).isNull();
        assertThat(features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,8)).metrics().roa()).isNotNull();
    }

    @Test void samsung2020RollsBackWhenExisting2021EvidenceChanged() throws Exception {
        prepareSamsung2020(false);samsungReviews.review(2022);
        jdbc.update("UPDATE financial_report_version SET extraction_profile='CHANGED' WHERE fiscal_year=2021");
        assertThatThrownBy(() -> samsungReviews.review(2021)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2020",Integer.class)).isZero();
    }

    @Test void samsung2020UnreviewedCorrectionBlocks2021CrossMetricsWithoutFallback() throws Exception {
        long id=prepareSamsung2020(false);samsungReviews.review(2021);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20220601000009',DATE '2022-06-01','[기재정정]사업보고서 (2020.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,6,1)).metrics().roa()).isNotNull();
        var after=features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,6,2));
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().roa()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    @Test void samsungFuture2021ChangeKeeps2020ValuesMetricsAndExport() throws Exception {
        long id=prepareSamsung2020(false);samsungReviews.review(2021);
        var date=LocalDate.of(2021,3,10);
        var before=features.create(id,2020,ReportType.ANNUAL,StatementType.CFS,date);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2021)");
        assertThat(features.create(id,2020,ReportType.ANNUAL,StatementType.CFS,date)).isEqualTo(before);
        assertThat(exports.export("00126380",2020,StatementType.CFS,java.util.List.of(date)).rows()).containsExactly(before);
    }

    @Test void missingSamsung2020TotalCannotBeFilledFrom2021Comparative() throws Exception {
        long id=prepareSamsung2020(false);samsungReviews.review(2021);
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2020)");
        assertThat(features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,9)).comparisonEvidence()).isNull();
    }

    private long prepareHynix(boolean mismatch) throws Exception {
        long id=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00164779','Synthetic Hynix fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,id,"7".repeat(64));
        for(int year:java.util.List.of(2024,2025)) {
            String receipt=year==2024 ? "20250319000665":"20260317000635";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,false,?)
                """,id,receipt,year==2024 ? LocalDate.of(2025,3,19):LocalDate.of(2026,3,17),"사업보고서 ("+year+".12)",raw);
            String xml=HynixAnnualReportParserTest.fixture(year);
            if(year==2025 && mismatch) xml=xml.replace("66,192,960","66,192,961");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }

    @Test void hynixPairCalculatesFromItsOwnCompanyAndPreservesIdempotency() throws Exception {
        long id=prepareHynix(false);hynixReviews.review();hynixReviews.review();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        assertThat(result.comparisonEvidence().comparisonProfile()).isEqualTo("HYNIX_2024_2025_CFS_SIX_TOTALS_V1");
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20250319000665");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("46.7629");
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("48.5928");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("45.9455");
        assertThat(result.metrics().roa()).isEqualByComparingTo("29.0225");
        assertThat(result.metrics().roe()).isEqualByComparingTo("44.1437");
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,20)).metrics().roa()).isNull();
        assertThatThrownBy(() -> features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,17)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void hynixMismatchStoresOriginalsWithoutApprovingCrossPeriodMetrics() throws Exception {
        long id=prepareHynix(true);
        assertThat(hynixReviews.review()).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18)).metrics().revenueGrowth()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isZero();
    }

    @Test void hynixSecondPublicationFailureRollsBackBothVersions() throws Exception {
        prepareHynix(false);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2026-03-16' WHERE receipt_number='20260317000635'");
        assertThatThrownBy(() -> hynixReviews.review()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isZero();
    }

    @Test void laterUnreviewedHynixCorrectionDoesNotBorrowSamsungOrOldHynixApproval() throws Exception {
        long id=prepareHynix(false);hynixReviews.review();
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20260401000002',DATE '2026-04-01','[기재정정]사업보고서 (2024.12)',true,?)
            """,id,raw);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,2));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    private long prepareHynix2023(boolean mismatch) throws Exception {
        long id=prepareHynix(false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20240319000684',DATE '2024-03-19','사업보고서 (2023.12)',false,?)
            """,id,raw);
        String xml=HynixAnnualReportParserTest.fixture(2023);
        if(mismatch) xml=xml.replace("32,765,719","32,765,718");
        byte[] zip=Hyundai2016ReportParserTest.zip("20240319000684",xml,StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,'20240319000684',?,?)",
            id,ReportVersionCollectionService.sha256(zip),zip);
        return id;
    }

    @Test void hynix2023AddsExact2024ComparisonWithoutChanging2025Evidence() throws Exception {
        long id=prepareHynix2023(false);hynixReviews.review();
        var original=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        hynixReviews.review(2024);hynixReviews.review(2024);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        var result=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,20));
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20240319000684");
        assertThat(result.comparisonEvidence().previousValues().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("-9137547000000");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("102.0189");
        assertThat(result.metrics().roa()).isEqualByComparingTo("17.9820");
        assertThat(result.metrics().roe()).isEqualByComparingTo("31.0736");
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18))).isEqualTo(original);
        var oldest=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,20));
        assertThat(oldest.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("32765719000000");
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.metrics().roe()).isNull();
        assertThatThrownBy(() -> features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void hynix2023MismatchCannotApprove2024OrBorrow2025Approval() throws Exception {
        long id=prepareHynix2023(true);hynixReviews.review();
        assertThat(hynixReviews.review(2024)).containsEntry("crossPeriodMetricsApproved",false);
        var result=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,20));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18)).metrics().revenueGrowth()).isNotNull();
    }

    @Test void hynix2023ImportRollsBackWhenExisting2024EvidenceChanged() throws Exception {
        prepareHynix2023(false);hynixReviews.review();
        jdbc.update("UPDATE financial_report_version SET extraction_profile='CHANGED' WHERE fiscal_year=2024");
        assertThatThrownBy(() -> hynixReviews.review(2024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2023",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
    }

    @Test void hynix2023UnreviewedCorrectionBlocks2024ComparisonAfterItsPublicDay() throws Exception {
        long id=prepareHynix2023(false);hynixReviews.review(2024);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250601000009',DATE '2025-06-01','[기재정정]사업보고서 (2023.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,6,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,6,2));
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    @Test void changingHynixFuture2024AmountKeeps2023FeaturesAndExportUnchanged() throws Exception {
        long id=prepareHynix2023(false);hynixReviews.review(2024);
        var date=LocalDate.of(2024,3,20);
        var before=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,date);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,date)).isEqualTo(before);
        assertThat(exports.export("00164779",2023,StatementType.CFS,java.util.List.of(date)).rows()).containsExactly(before);
    }

    @Test void missingHynix2023AccountCannotUse2024ComparativeToFillIt() throws Exception {
        long id=prepareHynix2023(false);hynixReviews.review(2024);
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,20)).comparisonEvidence()).isNull();
    }

    private long prepareKepco(boolean mismatch,boolean changedFuture) throws Exception {
        long id=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00159193','Synthetic KEPCO fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,id,"9".repeat(64));
        for(int year:java.util.List.of(2024,2025)) {
            String receipt=year==2024 ? "20250318000747":"20260317000686";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,false,?)
                """,id,receipt,year==2024 ? LocalDate.of(2025,3,18):LocalDate.of(2026,3,17),"사업보고서 ("+year+".12)",raw);
            String xml=KepcoAnnualReportParserTest.fixture(year);
            if(year==2025 && mismatch) xml=xml.replace("93,398,896","93,398,897");
            if(year==2025 && changedFuture) xml=xml.replace("97,429,346","99,999,999");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }

    @Test void kepcoExactPairProvidesFiveMetricsOnlyAfterPublicationAndIsIdempotent() throws Exception {
        long id=prepareKepco(false,false);kepcoReviews.review();kepcoReviews.review();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        assertThat(result.evidence().receiptNumber()).isEqualTo("20260317000686");
        assertThat(result.values().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("8666656000000");
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20250318000747");
        assertThat(result.comparisonEvidence().comparisonProfile()).isEqualTo("KEPCO_2024_2025_CFS_SIX_TOTALS_V1");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("4.3153");
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("13.8465");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("416.8537");
        assertThat(result.metrics().roa()).isEqualByComparingTo("3.4547");
        assertThat(result.metrics().roe()).isEqualByComparingTo("19.1136");
        assertThat(result.forecastReady()).isFalse();
        var oldest=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.unavailableMetricReasons()).containsEntry("roe","PREVIOUS_PERIOD_BASIS_NOT_VERIFIED");
        assertThatThrownBy(() -> features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,17)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void kepcoComparativeMismatchKeepsOriginalsButDoesNotApproveMetrics() throws Exception {
        long id=prepareKepco(true,false);
        assertThat(kepcoReviews.review()).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    @Test void kepcoWrongSecondPublicationRollsBackBothReviewedVersions() throws Exception {
        prepareKepco(false,false);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2026-03-16' WHERE receipt_number='20260317000686'");
        assertThatThrownBy(() -> kepcoReviews.review()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isZero();
    }

    @Test void kepcoUnreviewedPreviousCorrectionSuppressesGrowthWithoutFallback() throws Exception {
        long id=prepareKepco(false,false);kepcoReviews.review();
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20260401000009',DATE '2026-04-01','[기재정정]사업보고서 (2024.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,2));
        assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    @Test void kepcoChangedPreviousTotalCannotReuseApproval() throws Exception {
        long id=prepareKepco(false,false);kepcoReviews.review();
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roa()).isNull();
    }

    @Test void kepcoMissingPreviousTotalCannotBeFilledFromComparativeColumn() throws Exception {
        long id=prepareKepco(false,false);kepcoReviews.review();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roe()).isNull();
    }

    @Test void kepcoDifferentFutureRevenueCannotChangePastValuesOrExport() throws Exception {
        long id=prepareKepco(false,true);kepcoReviews.review();
        var past=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(past.evidence().receiptNumber()).isEqualTo("20250318000747");
        assertThat(past.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("93398896000000");
        assertThat(past.metrics().operatingMargin()).isEqualByComparingTo("8.9559");
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2025)");
        var unchanged=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(unchanged.values()).isEqualTo(past.values());
        assertThat(unchanged.metrics()).isEqualTo(past.metrics());
        assertThat(unchanged.evidence()).isEqualTo(past.evidence());
        var export=exports.export("00159193",2024,StatementType.CFS,java.util.List.of(LocalDate.of(2025,3,19),LocalDate.of(2026,3,18)));
        assertThat(export.exportedRows()).isEqualTo(2);
        assertThat(export.rows()).allSatisfy(row -> assertThat(row.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("93398896000000"));
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18)).values().get(StandardAccount.REVENUE))
            .isEqualByComparingTo("100000000000000");
    }

    private long prepareKepco2023(boolean changedCorrection) throws Exception {
        long id=prepareKepco(false,false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        for(boolean corrected:java.util.List.of(false,true)) {
            String receipt=corrected ? "20240329002856":"20240318000954";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,?,?)
                """,id,receipt,corrected ? LocalDate.of(2024,3,29):LocalDate.of(2024,3,18),
                (corrected ? "[기재정정]":"")+"사업보고서 (2023.12)",corrected,raw);
            String xml=KepcoAnnualReportParserTest.fixture2023(corrected);
            if(corrected && changedCorrection) xml=xml.replace("88,219,461","88,219,462");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }

    @Test void kepco2023PreservesTwoVersionsAndAdds2024PairWithoutChanging2025() throws Exception {
        long id=prepareKepco2023(false);kepcoReviews.review();
        var before=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18));
        kepcoReviews.review(2024);kepcoReviews.review(2024);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        var result=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20240329002856");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("5.8711");
        assertThat(result.metrics().roa()).isEqualByComparingTo("1.4889");
        assertThat(result.metrics().roe()).isEqualByComparingTo("9.2130");
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,18))).isEqualTo(before);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,29)).evidence().receiptNumber()).isEqualTo("20240318000954");
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,30)).evidence().receiptNumber()).isEqualTo("20240329002856");
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,30)).metrics().roa()).isNull();
        assertThatThrownBy(() -> features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,18)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void kepcoDifferentCorrectionCannotLeakIntoOriginalOrReuse2024Approval() throws Exception {
        long id=prepareKepco2023(true);
        assertThat(kepcoReviews.review(2024)).containsEntry("crossPeriodMetricsApproved",false);
        var original=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(original.evidence().receiptNumber()).isEqualTo("20240318000954");
        assertThat(original.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("88219461000000");
        var correction=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,30));
        assertThat(correction.evidence().receiptNumber()).isEqualTo("20240329002856");
        assertThat(correction.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("88219462000000");
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE receipt_number='20240329002856')");
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19))).isEqualTo(original);
        assertThat(exports.export("00159193",2023,StatementType.CFS,java.util.List.of(LocalDate.of(2024,3,19))).rows()).containsExactly(original);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19)).comparisonEvidence()).isNull();
    }

    @Test void kepco2023ThreeVersionImportRollsBackBothNewVersionsOnChanged2024Evidence() throws Exception {
        prepareKepco2023(false);kepcoReviews.review();
        jdbc.update("UPDATE financial_report_version SET extraction_profile='CHANGED' WHERE fiscal_year=2024");
        assertThatThrownBy(() -> kepcoReviews.review(2024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2023",Integer.class)).isZero();
    }

    @Test void missingKepco2023CorrectionAmountCannotFallBackToOriginalOrComparative() throws Exception {
        long id=prepareKepco2023(false);kepcoReviews.review(2024);
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE receipt_number='20240329002856')");
        assertThatThrownBy(() -> features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,30)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19)).comparisonEvidence()).isNull();
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19)).values().get(StandardAccount.REVENUE))
            .isEqualByComparingTo("88219461000000");
    }

    @Test void unreviewedNewKepco2023CorrectionBlocksBothHistoricalScopeAnd2024Comparison() throws Exception {
        long id=prepareKepco2023(false);kepcoReviews.review(2024);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250401000009',DATE '2025-04-01','[기재정정]사업보고서 (2023.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,1)).metrics().roa()).isNotNull();
        var after=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,2));
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().roa()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    private long prepare2022Pair(boolean kepco,boolean mismatch) throws Exception {
        long id=kepco ? prepareKepco2023(false):prepareHynix2023(false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        var receipts=kepco ? java.util.List.of("20230320001084","20230410003765","20230525000309") : java.util.List.of("20230321001209");
        var dates=Map.of("20230320001084",LocalDate.of(2023,3,20),"20230410003765",LocalDate.of(2023,4,10),
            "20230525000309",LocalDate.of(2023,5,25),"20230321001209",LocalDate.of(2023,3,21));
        for(String receipt:receipts) {
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,'사업보고서 (2022.12)',?,?)
                """,id,receipt,dates.get(receipt),!receipt.equals(receipts.getFirst()),raw);
            String xml=kepco ? KepcoAnnualReportParserTest.fixture2022(receipt):HynixAnnualReportParserTest.fixture(2022);
            if(mismatch && receipt.equals(receipts.getLast())) xml=xml.replace(kepco ? "71,257,863":"44,621,568",kepco ? "71,257,864":"44,621,569");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }
    private Map<String,Object> review2023Pair(boolean kepco) {
        return kepco ? kepcoReviews.review(2023):hynixReviews.review(2023);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exact2023PairAllowsPriorCurrentTotalsButPreserves2022PolicyHold(boolean kepco) throws Exception {
        long id=prepare2022Pair(kepco,false);
        if(kepco) kepcoReviews.review(2024); else hynixReviews.review(2024);
        var futureBefore=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,1));
        assertThat(review2023Pair(kepco)).containsEntry("crossPeriodMetricsApproved",true); review2023Pair(kepco);
        var result=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,1,6));
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo(kepco ? "20230525000309":"20230321001209");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo(kepco ? "23.8031":"-26.5698");
        assertThat(result.metrics().roa()).isNotNull(); assertThat(result.metrics().roe()).isNotNull();
        assertThat(result.forecastReady()).isFalse();
        var old=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,1,6));
        assertThat(old.analysisAllowed()).isFalse(); assertThat(old.metrics().revenueGrowth()).isNull();
        assertThat(old.metrics().operatingMargin()).isNull();
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,1))).isEqualTo(futureBefore);
        String profile=kepco ? "KEPCO_2022_2023_CFS_SIX_TOTALS_V1":"HYNIX_2022_2023_CFS_SIX_TOTALS_V1";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review WHERE comparison_profile=?",Integer.class,profile)).isEqualTo(1);
        jdbc.update("UPDATE financial_period_comparison_review SET evidence=evidence-'previousPeriodExclusionReason' WHERE comparison_profile=?",profile);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,1,6)).comparisonEvidence()).isNull();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void mismatched2022TotalsCannotApprove2023(boolean kepco) throws Exception {
        long id=prepare2022Pair(kepco,true);
        assertThat(review2023Pair(kepco)).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,1,6)).metrics().roa()).isNull();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void futurePreviousCorrectionDoesNotLeakOrFallBackToApproved2022(boolean kepco) throws Exception {
        long id=prepare2022Pair(kepco,false);review2023Pair(kepco);
        var date=LocalDate.of(2025,1,6);
        var before=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,date);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250201000001',DATE '2025-02-01','[기재정정]사업보고서 (2022.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,date)).isEqualTo(before);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,2,1)).metrics().roa()).isNotNull();
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,2,2)).comparisonEvidence()).isNull();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void changed2022ValuesOrPolicyReasonRevokes2023Permission(boolean kepco) throws Exception {
        long id=prepare2022Pair(kepco,false);review2023Pair(kepco);
        var current=queries.find(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,1,6));
        String corp=kepco ? "00159193":"00164779";
        var newPolicy=new io.corpscope.analysis.AnalysisEligibilityProperties(Map.of(),Map.of(corp+":2022:ANNUAL:CFS","New unresolved current totals issue"));
        assertThat(new FinancialPeriodComparisonService(jdbc,queries,newPolicy).resolve(current)).isEmpty();
        var companyPolicy=new io.corpscope.analysis.AnalysisEligibilityProperties(Map.of(corp,kepco ? FinancialPeriodComparisonService.KEPCO_2022_COMPARISON_HOLD:FinancialPeriodComparisonService.HYNIX_2022_COMPARISON_HOLD),Map.of());
        assertThat(new FinancialPeriodComparisonService(jdbc,queries,companyPolicy).resolve(current)).isEmpty();
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='ASSETS' AND version_id IN (SELECT id FROM financial_report_version WHERE fiscal_year=2022)");
        assertThat(comparisons.resolve(current)).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void new2022VersionsRollBackIfExisting2023EvidenceChanged(boolean kepco) throws Exception {
        prepare2022Pair(kepco,false);
        if(kepco) kepcoReviews.review(2024); else hynixReviews.review(2024);
        jdbc.update("UPDATE financial_report_version SET extraction_profile='CHANGED' WHERE fiscal_year=2023");
        assertThatThrownBy(() -> review2023Pair(kepco)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2022",Integer.class)).isZero();
    }
    @Test void kepco2022CorrectionDatesSelectActualVersionValuesWhile2023OriginalRemainsUnapproved() throws Exception {
        long id=prepare2022Pair(true,false);
        // Different source values in isolation prove version selection, although official six totals match.
        String receipt="20230320001084";
        String xml=KepcoAnnualReportParserTest.fixture2022(receipt).replace("71,257,863","70,000,000");
        byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
        jdbc.update("UPDATE report_document SET zip_bytes=?,sha256=? WHERE receipt_number=?",zip,ReportVersionCollectionService.sha256(zip),receipt);
        kepcoReviews.review(2024);kepcoReviews.review(2023);
        var before=queries.find(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,10));
        assertThat(before.receiptNumber()).isEqualTo(receipt);
        assertThat(before.accounts()).filteredOn(a -> a.standardAccount()==StandardAccount.REVENUE).allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("70000000000000"));
        assertThat(queries.find(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,11)).receiptNumber()).isEqualTo("20230410003765");
        assertThat(queries.find(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,5,25)).receiptNumber()).isEqualTo("20230410003765");
        var corrected=queries.find(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,5,26));
        assertThat(corrected.receiptNumber()).isEqualTo("20230525000309");
        assertThat(corrected.accounts()).filteredOn(a -> a.standardAccount()==StandardAccount.REVENUE).allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("71257863000000"));
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,29)).comparisonEvidence()).isNull();
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,30)).comparisonEvidence()).isNotNull();
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE receipt_number='20230525000309')");
        assertThat(queries.find(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,10))).isEqualTo(before);
    }
    private long prepareSamsung(boolean changedComparative) throws Exception {
        long id=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00126380','Synthetic Samsung fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,id,"8".repeat(64));
        for(int year:java.util.List.of(2024,2025)) {
            String receipt=year==2024 ? "20250311001085":"20260310002820";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,false,?)
                """,id,receipt,year==2024 ? LocalDate.of(2025,3,11):LocalDate.of(2026,3,10),"사업보고서 ("+year+".12)",raw);
            String xml=SamsungAnnualReportParserTest.fixture(year);
            if(year==2025 && changedComparative) xml=xml.replace("300,870,903","300,870,904");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }

    @Test void verifiedSamsungPairCalculatesFiveMetricsWithBothPublicSources() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        var result=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.schemaVersion()).isEqualTo("FINANCIAL_AS_OF_V2");
        assertThat(result.comparisonEvidence().previousFiscalYear()).isEqualTo(2024);
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20250311001085");
        assertThat(result.comparisonEvidence().evidenceAvailableFromKstDate()).isEqualTo(LocalDate.of(2026,3,11));
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("10.8801");
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("13.0696");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("29.9371");
        assertThat(result.metrics().roa()).isEqualByComparingTo("8.3602");
        assertThat(result.metrics().roe()).isEqualByComparingTo("10.7826");
        assertThat(result.unavailableMetricReasons()).isEmpty();
        assertThat(result.forecastReady()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
        assertThat(features.create(samsung,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11)).metrics().revenueGrowth()).isNull();
    }

    @Test void futureComparisonEvidenceCannotEnableCrossPeriodMetrics() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        jdbc.update("UPDATE financial_period_comparison_review SET evidence_available_from_kst_date=DATE '2030-01-01'");
        var result=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    @Test void laterUnreviewedPreviousYearCorrectionSuppressesCrossMetricsWithoutOlderFallback() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        Long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,samsung);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20260401000001',DATE '2026-04-01','[기재정정]사업보고서 (2024.12)',true,?)
            """,samsung,raw);
        var before=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,1));
        assertThat(before.metrics().revenueGrowth()).isNotNull();
        var after=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,2));
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
        assertThat(exports.export("00126380",2025,StatementType.CFS,java.util.List.of(LocalDate.of(2026,4,2))).exportedRows()).isEqualTo(1);
    }

    @Test void incompletePreviousVersionCannotBeReplacedByCurrentReportComparativeColumn() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    @Test void previousPeriodHoldSuppressesCrossMetricsEvenWithApprovedNumericPair() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        var policy=new io.corpscope.analysis.AnalysisEligibilityProperties(Map.of(),Map.of("00126380:2024:ANNUAL:CFS","Prior period hold"));
        var comparator=new FinancialPeriodComparisonService(jdbc,queries,policy);
        var guarded=new FinancialFeatureService(queries,new io.corpscope.analysis.FinancialMetricsCalculator(),policy,comparator);
        var result=guarded.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.analysisAllowed()).isTrue();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.metrics().debtRatio()).isNotNull();
    }

    @Test void changedPreviousAmountsCannotReuseStalePairApproval() throws Exception {
        long samsung=prepareSamsung(false); samsungReviews.review();
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(samsung,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,11));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings={"bad-date","2017-02-30"})
    void featureApiRejectsInvalidDates(String date) throws Exception {
        mvc.perform(get("/api/companies/{id}/financials/as-of/features",company).param("fiscalYear","2016").param("asOf",date))
            .andExpect(status().isBadRequest());
    }

    private void insertLatestFact(int year,String source) {
        Long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'fnlttSinglAcntAll',?,?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,company,year+":ANNUAL:CFS","2".repeat(64));
        jdbc.update("""
            INSERT INTO financial_fact(company_id,fiscal_year,report_type,statement_type,statement_division,
              account_key,standard_account,raw_account_name,amount,currency,receipt_number,source,raw_ingestion_id)
            VALUES (?,?,'ANNUAL','CFS','IS',?,'REVENUE','Synthetic isolated latest fact',999999,'KRW','20220217000946',?,?)
            """,company,year,"3".repeat(64),source,raw);
    }

    FinancialAsOfResponse find(String date) { return queries.find(company,2016,ReportType.ANNUAL,StatementType.CFS,LocalDate.parse(date)); }
    private long prepareNaver(boolean mismatch,boolean changedFuture) throws Exception {
        long id=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00266961','Synthetic NAVER fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20160101:20261013:A001:N:1',?,CAST('{"status":"000"}' AS jsonb)) RETURNING id
            """,Long.class,id,"9".repeat(64));
        for(int year:java.util.List.of(2024,2025)) {
            String receipt=year==2024 ? "20250318000645":"20260313001021";
            jdbc.update("""
                INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                VALUES (?,?,?,?,false,?)
                """,id,receipt,year==2024 ? LocalDate.of(2025,3,18):LocalDate.of(2026,3,13),"사업보고서 ("+year+".12)",raw);
            String xml=NaverAnnualReportParserTest.fixture(year);
            if(year==2025 && mismatch) xml=xml.replace("10,737,719,264,647","10,737,719,264,648");
            if(year==2025 && changedFuture) xml=xml.replace("12,035,007,218,975","12,999,999,999,999");
            byte[] zip=Hyundai2016ReportParserTest.zip(receipt,xml,StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
                id,receipt,ReportVersionCollectionService.sha256(zip),zip);
        }
        return id;
    }

    @Test void naverExactPairProvidesFiveMetricsOnlyAfterPublicationAndIsIdempotent() throws Exception {
        long id=prepareNaver(false,false);naverReviews.review();naverReviews.review();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE display_unit=1",Integer.class)).isEqualTo(12);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        assertThat(result.evidence().receiptNumber()).isEqualTo("20260313001021");
        assertThat(result.values().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("1818746310015");
        assertThat(result.comparisonEvidence().previous().receiptNumber()).isEqualTo("20250318000645");
        assertThat(result.comparisonEvidence().comparisonProfile()).isEqualTo("NAVER_2024_2025_CFS_SIX_TOTALS_V1");
        assertThat(result.metrics().revenueGrowth()).isEqualByComparingTo("12.0816");
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("18.3476");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("41.9006");
        assertThat(result.metrics().roa()).isEqualByComparingTo("4.5898");
        assertThat(result.metrics().roe()).isEqualByComparingTo("6.5009");
        assertThat(result.forecastReady()).isFalse();
        var oldest=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.unavailableMetricReasons()).containsEntry("roe","PREVIOUS_PERIOD_BASIS_NOT_VERIFIED");
        assertThatThrownBy(() -> features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,13)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
    }

    @Test void naverComparativeMismatchKeepsOriginalsButDoesNotApproveMetrics() throws Exception {
        long id=prepareNaver(true,false);
        assertThat(naverReviews.review()).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(2);
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().operatingMargin()).isNotNull();
    }

    @Test void naverWrongSecondPublicationRollsBackBothReviewedVersions() throws Exception {
        prepareNaver(false,false);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2026-03-12' WHERE receipt_number='20260313001021'");
        assertThatThrownBy(() -> naverReviews.review()).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact",Integer.class)).isZero();
    }

    @Test void naverUnreviewedPreviousCorrectionSuppressesGrowthWithoutFallback() throws Exception {
        long id=prepareNaver(false,false);naverReviews.review();
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20260401000009',DATE '2026-04-01','[기재정정]사업보고서 (2024.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,4,2));
        assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.comparisonEvidence()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }

    @Test void naverChangedPreviousTotalCannotReuseApproval() throws Exception {
        long id=prepareNaver(false,false);naverReviews.review();
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roa()).isNull();
    }

    @Test void naverMissingPreviousTotalCannotBeFilledFromComparativeColumn() throws Exception {
        long id=prepareNaver(false,false);naverReviews.review();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var result=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        assertThat(result.comparisonEvidence()).isNull();
        assertThat(result.metrics().roe()).isNull();
    }

    @Test void naverDifferentFutureRevenueCannotChangePastValuesOrExport() throws Exception {
        long id=prepareNaver(false,true);naverReviews.review();
        var past=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(past.evidence().receiptNumber()).isEqualTo("20250318000645");
        assertThat(past.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("10737719264647");
        assertThat(past.metrics().operatingMargin()).isEqualByComparingTo("18.4328");
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2025)");
        var unchanged=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(unchanged.values()).isEqualTo(past.values());
        assertThat(unchanged.metrics()).isEqualTo(past.metrics());
        assertThat(unchanged.evidence()).isEqualTo(past.evidence());
        var export=exports.export("00266961",2024,StatementType.CFS,java.util.List.of(LocalDate.of(2025,3,19),LocalDate.of(2026,3,14)));
        assertThat(export.exportedRows()).isEqualTo(2);
        assertThat(export.rows()).allSatisfy(row -> assertThat(row.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("10737719264647"));
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14)).values().get(StandardAccount.REVENUE))
            .isEqualByComparingTo("13000000999999");
    }


    private long prepareNaver2023(boolean mismatch) throws Exception {
        long id=prepareNaver(false,false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20240318000844',DATE '2024-03-18','사업보고서 (2023.12)',false,?)
            """,id,raw);
        String xml=NaverAnnualReportParserTest.fixture(2023);
        if(mismatch) xml=xml.replace("9,670,643,576,585","9,670,643,576,586");
        byte[] zip=Hyundai2016ReportParserTest.zip("20240318000844",xml,StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
            id,"20240318000844",ReportVersionCollectionService.sha256(zip),zip);
        return id;
    }
    @Test void naver2023Adds2024PairAndPreserves2025Approval() throws Exception {
        long id=prepareNaver2023(false);naverReviews.review();
        var later=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        naverReviews.review(2024);naverReviews.review(2024);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE display_unit=1",Integer.class)).isEqualTo(18);
        var current=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(current.metrics().revenueGrowth()).isEqualByComparingTo("11.0342");
        assertThat(current.metrics().roa()).isEqualByComparingTo("5.2282");
        assertThat(current.metrics().roe()).isEqualByComparingTo("7.5411");
        assertThat(current.comparisonEvidence().previous().receiptNumber()).isEqualTo("20240318000844");
        assertThat(current.comparisonEvidence().comparisonProfile()).isEqualTo("NAVER_2023_2024_CFS_SIX_TOTALS_V1");
        var oldest=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(oldest.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("9670643576585");
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.metrics().roa()).isNull();
        assertThat(oldest.metrics().roe()).isNull();
        assertThatThrownBy(() -> features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,18)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
        var unchanged=features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14));
        assertThat(unchanged.metrics()).isEqualTo(later.metrics());
        assertThat(unchanged.comparisonEvidence()).isEqualTo(later.comparisonEvidence());
    }
    @Test void naver2023MismatchDoesNotBorrowApprovalFrom2025() throws Exception {
        long id=prepareNaver2023(true);naverReviews.review();
        assertThat(naverReviews.review(2024)).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19)).metrics().revenueGrowth()).isNull();
        assertThat(features.create(id,2025,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2026,3,14)).metrics().revenueGrowth()).isNotNull();
    }
    @Test void naver2023PairFailureRollsBackWithoutChangingLaterReview() throws Exception {
        prepareNaver2023(false);naverReviews.review();
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2025-03-17' WHERE receipt_number='20250318000645'");
        assertThatThrownBy(() -> naverReviews.review(2024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2023",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
    }
    @Test void naver2023MissingOrChangedAmountCannotUseComparativeBackfill() throws Exception {
        long id=prepareNaver2023(false);naverReviews.review(2024);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19)).comparisonEvidence()).isNull();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        var result=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(result.metrics().roa()).isNull();assertThat(result.metrics().roe()).isNull();
    }
    @Test void naver2023UnreviewedCorrectionSuppresses2024ComparisonAtItsPublicationBoundary() throws Exception {
        long id=prepareNaver2023(false);naverReviews.review(2024);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250401000009',DATE '2025-04-01','[기재정정]사업보고서 (2023.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,4,2));
        assertThat(after.comparisonEvidence()).isNull();assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }
    @Test void futureNaver2024MutationCannotChange2023ValuesMetricsOrExport() throws Exception {
        long id=prepareNaver2023(false);naverReviews.review(2024);
        var past=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2024)");
        var unchanged=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(unchanged.values()).isEqualTo(past.values());assertThat(unchanged.metrics()).isEqualTo(past.metrics());
        assertThat(unchanged.evidence()).isEqualTo(past.evidence());
        var export=exports.export("00266961",2023,StatementType.CFS,java.util.List.of(LocalDate.of(2024,3,19),LocalDate.of(2025,3,19)));
        assertThat(export.exportedRows()).isEqualTo(2);
        assertThat(export.rows()).allSatisfy(row -> {
            assertThat(row.evidence().receiptNumber()).isEqualTo("20240318000844");
            assertThat(row.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("9670643576585");
        });
    }

    private long prepareNaver2022(boolean mismatch) throws Exception {
        long id=prepareNaver2023(false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230314001049',DATE '2023-03-14','사업보고서 (2022.12)',false,?)
            """,id,raw);
        String xml=NaverAnnualReportParserTest.fixture(2022);
        if(mismatch) xml=xml.replace("8,220,078,708,810","8,220,078,708,811");
        byte[] zip=Hyundai2016ReportParserTest.zip("20230314001049",xml,StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
            id,"20230314001049",ReportVersionCollectionService.sha256(zip),zip);
        return id;
    }
    @Test void naver2022Adds2023PairAndPreserves2024Approval() throws Exception {
        long id=prepareNaver2022(false);naverReviews.review(2024);
        var later=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        naverReviews.review(2023);naverReviews.review(2023);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE display_unit=1",Integer.class)).isEqualTo(18);
        var current=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(current.metrics().revenueGrowth()).isEqualByComparingTo("17.6466");
        assertThat(current.metrics().roa()).isEqualByComparingTo("2.8290");
        assertThat(current.metrics().roe()).isEqualByComparingTo("4.1311");
        assertThat(current.comparisonEvidence().previous().receiptNumber()).isEqualTo("20230314001049");
        assertThat(current.comparisonEvidence().comparisonProfile()).isEqualTo("NAVER_2022_2023_CFS_SIX_TOTALS_V1");
        var oldest=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15));
        assertThat(oldest.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("8220078708810");
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.metrics().roa()).isNull();
        assertThat(oldest.metrics().roe()).isNull();
        assertThatThrownBy(() -> features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,14)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
        var unchanged=features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19));
        assertThat(unchanged.metrics()).isEqualTo(later.metrics());
        assertThat(unchanged.comparisonEvidence()).isEqualTo(later.comparisonEvidence());
    }
    @Test void naver2022MismatchDoesNotBorrowApprovalFrom2024() throws Exception {
        long id=prepareNaver2022(true);naverReviews.review(2024);
        assertThat(naverReviews.review(2023)).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19)).metrics().revenueGrowth()).isNull();
        assertThat(features.create(id,2024,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2025,3,19)).metrics().revenueGrowth()).isNotNull();
    }
    @Test void naver2022PairFailureRollsBackWithoutChangingLaterReview() throws Exception {
        prepareNaver2022(false);naverReviews.review(2024);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2024-03-17' WHERE receipt_number='20240318000844'");
        assertThatThrownBy(() -> naverReviews.review(2023)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2022",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
    }
    @Test void naver2022MissingOrChangedAmountCannotUseComparativeBackfill() throws Exception {
        long id=prepareNaver2022(false);naverReviews.review(2023);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2022)");
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19)).comparisonEvidence()).isNull();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2022)");
        var result=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(result.metrics().roa()).isNull();assertThat(result.metrics().roe()).isNull();
    }
    @Test void naver2022UnreviewedCorrectionSuppresses2023ComparisonAtItsPublicationBoundary() throws Exception {
        long id=prepareNaver2022(false);naverReviews.review(2023);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20240401000009',DATE '2024-04-01','[기재정정]사업보고서 (2022.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,4,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,4,2));
        assertThat(after.comparisonEvidence()).isNull();assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }
    @Test void futureNaver2023MutationCannotChange2022ValuesMetricsOrExport() throws Exception {
        long id=prepareNaver2022(false);naverReviews.review(2023);
        var past=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15));
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2023)");
        var unchanged=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15));
        assertThat(unchanged.values()).isEqualTo(past.values());assertThat(unchanged.metrics()).isEqualTo(past.metrics());
        assertThat(unchanged.evidence()).isEqualTo(past.evidence());
        var export=exports.export("00266961",2022,StatementType.CFS,java.util.List.of(LocalDate.of(2023,3,15),LocalDate.of(2024,3,19)));
        assertThat(export.exportedRows()).isEqualTo(2);
        assertThat(export.rows()).allSatisfy(row -> {
            assertThat(row.evidence().receiptNumber()).isEqualTo("20230314001049");
            assertThat(row.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("8220078708810");
        });
    }

    private long prepareNaver2021(boolean mismatch) throws Exception {
        long id=prepareNaver2022(false);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20220304000687',DATE '2022-03-04','사업보고서 (2021.12)',false,?)
            """,id,raw);
        String xml=NaverAnnualReportParserTest.fixture(2021);
        if(mismatch) xml=xml.replace("6,817,599,707,250","6,817,599,707,251");
        byte[] zip=Hyundai2016ReportParserTest.zip("20220304000687",xml.replace("UTF-8","MS949"),java.nio.charset.Charset.forName("MS949"));
        jdbc.update("INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)",
            id,"20220304000687",ReportVersionCollectionService.sha256(zip),zip);
        return id;
    }
    @Test void naver2021Adds2022PairAndPreserves2023Approval() throws Exception {
        long id=prepareNaver2021(false);naverReviews.review(2023);
        var later=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        naverReviews.review(2022);naverReviews.review(2022);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_version_fact WHERE display_unit=1",Integer.class)).isEqualTo(18);
        var current=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15));
        assertThat(current.warnings()).contains("REPORTED_BALANCES_INCLUDE_LINE_INTEGRATION_EFFECTS_NOT_ADJUSTED_RETURNS");
        assertThat(current.metrics().revenueGrowth()).isEqualByComparingTo("20.5714");
        assertThat(current.metrics().roa()).isEqualByComparingTo("1.9920");
        assertThat(current.metrics().roe()).isEqualByComparingTo("2.8358");
        assertThat(current.comparisonEvidence().previous().receiptNumber()).isEqualTo("20220304000687");
        assertThat(current.comparisonEvidence().comparisonProfile()).isEqualTo("NAVER_2021_2022_CFS_SIX_TOTALS_V1");
        var oldest=features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,5));
        assertThat(oldest.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("6817599707250");
        assertThat(oldest.values().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("16477625559645");
        assertThat(oldest.warnings()).contains("NET_INCOME_INCLUDES_LINE_DISCONTINUED_OPERATIONS_AND_DISPOSAL_GAIN");
        assertThat(oldest.metrics().revenueGrowth()).isNull();
        assertThat(oldest.metrics().roa()).isNull();
        assertThat(oldest.metrics().roe()).isNull();
        assertThatThrownBy(() -> features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,4)))
            .isInstanceOf(FinancialAsOfUnavailableException.class);
        var unchanged=features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19));
        assertThat(unchanged.metrics()).isEqualTo(later.metrics());
        assertThat(unchanged.comparisonEvidence()).isEqualTo(later.comparisonEvidence());
    }
    @Test void naver2021MismatchDoesNotBorrowApprovalFrom2023() throws Exception {
        long id=prepareNaver2021(true);naverReviews.review(2023);
        assertThat(naverReviews.review(2022)).containsEntry("crossPeriodMetricsApproved",false);
        assertThat(features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15)).metrics().revenueGrowth()).isNull();
        assertThat(features.create(id,2023,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2024,3,19)).metrics().revenueGrowth()).isNotNull();
    }
    @Test void naver2021PairFailureRollsBackWithoutChangingLaterReview() throws Exception {
        prepareNaver2021(false);naverReviews.review(2023);
        jdbc.update("UPDATE report_publication SET receipt_date=DATE '2023-03-13' WHERE receipt_number='20230314001049'");
        assertThatThrownBy(() -> naverReviews.review(2022)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_report_version WHERE fiscal_year=2021",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_period_comparison_review",Integer.class)).isEqualTo(1);
    }
    @Test void naver2021MissingOrChangedAmountCannotUseComparativeBackfill() throws Exception {
        long id=prepareNaver2021(false);naverReviews.review(2022);
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2021)");
        assertThat(features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15)).comparisonEvidence()).isNull();
        jdbc.update("DELETE FROM financial_version_fact WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2021)");
        var result=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,3,15));
        assertThat(result.metrics().roa()).isNull();assertThat(result.metrics().roe()).isNull();
    }
    @Test void naver2021UnreviewedCorrectionSuppresses2022ComparisonAtItsPublicationBoundary() throws Exception {
        long id=prepareNaver2021(false);naverReviews.review(2022);
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,id);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20230401000009',DATE '2023-04-01','[기재정정]사업보고서 (2021.12)',true,?)
            """,id,raw);
        assertThat(features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,1)).metrics().revenueGrowth()).isNotNull();
        var after=features.create(id,2022,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2023,4,2));
        assertThat(after.comparisonEvidence()).isNull();assertThat(after.metrics().revenueGrowth()).isNull();
        assertThat(after.metrics().operatingMargin()).isNotNull();
    }
    @Test void futureNaver2022MutationCannotChange2021ValuesMetricsOrExport() throws Exception {
        long id=prepareNaver2021(false);naverReviews.review(2022);
        var past=features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,5));
        jdbc.update("UPDATE financial_version_fact SET amount=amount+1000000 WHERE standard_account='REVENUE' AND version_id=(SELECT id FROM financial_report_version WHERE fiscal_year=2022)");
        var unchanged=features.create(id,2021,ReportType.ANNUAL,StatementType.CFS,LocalDate.of(2022,3,5));
        assertThat(unchanged.values()).isEqualTo(past.values());assertThat(unchanged.metrics()).isEqualTo(past.metrics());
        assertThat(unchanged.evidence()).isEqualTo(past.evidence());
        var export=exports.export("00266961",2021,StatementType.CFS,java.util.List.of(LocalDate.of(2022,3,5),LocalDate.of(2023,3,15)));
        assertThat(export.exportedRows()).isEqualTo(2);
        assertThat(export.rows()).allSatisfy(row -> {
            assertThat(row.evidence().receiptNumber()).isEqualTo("20220304000687");
            assertThat(row.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("6817599707250");
        });
    }
}
