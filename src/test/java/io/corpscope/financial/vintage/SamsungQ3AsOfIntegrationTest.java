package io.corpscope.financial.vintage;

import java.time.LocalDate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic receipt/version values in a disposable PostgreSQL database only. */
@Tag("integration") @Testcontainers @ActiveProfiles("demo") @SpringBootTest
class SamsungQ3AsOfIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.13");
    @Autowired JdbcTemplate jdbc;
    @Autowired SamsungQ3AsOfService service;
    @Autowired PeriodicAsOfService periodic;
    long company;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM quarterly_version_fact");
        jdbc.update("DELETE FROM quarterly_report_version");
        jdbc.update("DELETE FROM quarterly_filing_coverage");
        jdbc.update("DELETE FROM periodic_filing_coverage");
        jdbc.update("DELETE FROM report_document");
        jdbc.update("DELETE FROM report_publication");
        jdbc.update("DELETE FROM raw_ingestion");
        jdbc.update("DELETE FROM company");
        company=jdbc.queryForObject("INSERT INTO company(corp_code,name) VALUES ('00126380','Synthetic Samsung fixture') RETURNING id",Long.class);
        long raw=jdbc.queryForObject("""
            INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
            VALUES (?,'list','20250930:20251231:A003:N:1',repeat('a',64),'{}'::jsonb) RETURNING id
            """,Long.class,company);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20251114002447','2025-11-14','분기보고서 (2025.09)',false,?)
            """,company,raw);
        jdbc.update("""
            INSERT INTO quarterly_filing_coverage(company_id,checked_from,checked_through,total_pages,total_count)
            VALUES (?,'2025-09-30','2025-12-31',1,1)
            """,company);
        jdbc.update("""
            INSERT INTO periodic_filing_coverage(company_id,fiscal_year,report_type,detail_type,checked_from,
              checked_through,total_pages,total_count)
            VALUES (?,2025,'Q3','A003','2025-09-30','2025-12-31',1,1)
            """,company);
        jdbc.update("""
            INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes)
            VALUES (?,'20251114002447',repeat('b',64),decode('504b0304','hex'))
            """,company);
        long version=jdbc.queryForObject("""
            INSERT INTO quarterly_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
              main_entry,document_encoding,extraction_profile,evidence)
            VALUES (?,2025,'Q3','CFS','20251114002447','20251114002447.xml','UTF-8',
              'SYNTHETIC_Q3_TEST','{}'::jsonb) RETURNING id
            """,Long.class,company);
        for(String account:new String[]{"ASSETS","LIABILITIES","EQUITY","REVENUE","OPERATING_PROFIT","NET_INCOME"}) {
            boolean balance=account.equals("ASSETS") || account.equals("LIABILITIES") || account.equals("EQUITY");
            jdbc.update("""
                INSERT INTO quarterly_version_fact(version_id,standard_account,raw_account_name,statement_division,
                  amount,cumulative_amount,currency,display_unit)
                VALUES (?,?,?,?,?,?,'KRW',1000000)
                """,version,account,account,balance?"BS":"IS",
                balance?100000000L:200000000L,balance?null:500000000L);
        }
    }

    @Test void receiptDayIsUnavailableAndFollowingDayReturnsDistinctPeriods() {
        assertThatThrownBy(() -> service.find(company,LocalDate.of(2025,11,14)))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.NO_PUBLIC_FILING));
        var result=service.find(company,LocalDate.of(2025,11,15));
        assertThat(result.receiptNumber()).isEqualTo("20251114002447");
        assertThat(result.accounts()).hasSize(6);
        assertThat(result.accounts().stream().filter(a -> a.statementDivision().equals("BS")))
            .allMatch(a -> a.cumulativeAmount()==null);
        assertThat(result.accounts().stream().filter(a -> a.statementDivision().equals("IS")))
            .allMatch(a -> a.cumulativeAmount()!=null);
    }

    @Test void unreviewedNewerCorrectionBlocksOlderReviewedVersion() {
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,company);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20251201000001','2025-12-01','[기재정정]분기보고서 (2025.09)',true,?)
            """,company,raw);
        assertThat(service.find(company,LocalDate.of(2025,11,30)).receiptNumber()).isEqualTo("20251114002447");
        assertThatThrownBy(() -> service.find(company,LocalDate.of(2025,12,2)))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.UNREVIEWED_SELECTED_FILING));
    }

    @Test void outsideCompleteFilingCoverageIsWithheld() {
        assertThatThrownBy(() -> service.find(company,LocalDate.of(2026,1,2)))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.NO_FILING_COVERAGE));
    }

    @Test void genericPeriodicLookupUsesExactScopeAndBlocksUnreviewedCorrection() {
        var report=periodic.find(company,2025,io.corpscope.financial.ReportType.Q3,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,11,17));
        assertThat(report.receiptNumber()).isEqualTo("20251114002447");
        assertThat(report.accounts().stream().filter(a -> a.standardAccount().name().equals("REVENUE"))
            .findFirst().orElseThrow().cumulativeAmount()).isEqualByComparingTo("500000000");
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,company);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20251201000001','2025-12-01','[기재정정]분기보고서 (2025.09)',true,?)
            """,company,raw);
        assertThatThrownBy(() -> periodic.find(company,2025,io.corpscope.financial.ReportType.Q3,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,12,2)))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.UNREVIEWED_SELECTED_FILING));
        assertThat(periodic.find(company,2025,io.corpscope.financial.ReportType.Q3,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,11,17)).receiptNumber())
            .isEqualTo("20251114002447");
    }

    @Test void reviewedFirstQuarterUsesItsOwnPublicationAndThreeMonthAmounts() {
        long raw=jdbc.queryForObject("SELECT id FROM raw_ingestion WHERE company_id=?",Long.class,company);
        jdbc.update("""
            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
            VALUES (?,'20250515001922','2025-05-15','분기보고서 (2025.03)',false,?)
            """,company,raw);
        jdbc.update("""
            INSERT INTO periodic_filing_coverage(company_id,fiscal_year,report_type,detail_type,checked_from,
              checked_through,total_pages,total_count)
            VALUES (?,2025,'Q1','A003','2025-03-31','2025-12-31',1,1)
            """,company);
        jdbc.update("""
            INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes)
            VALUES (?,'20250515001922',repeat('c',64),decode('504b0304','hex'))
            """,company);
        long version=jdbc.queryForObject("""
            INSERT INTO quarterly_report_version(company_id,fiscal_year,report_type,statement_type,receipt_number,
              main_entry,document_encoding,extraction_profile,evidence)
            VALUES (?,2025,'Q1','CFS','20250515001922','20250515001922.xml','UTF-8',
              'SYNTHETIC_Q1_TEST','{}'::jsonb) RETURNING id
            """,Long.class,company);
        for(String account:new String[]{"ASSETS","LIABILITIES","EQUITY","REVENUE","OPERATING_PROFIT","NET_INCOME"}) {
            boolean balance=account.equals("ASSETS") || account.equals("LIABILITIES") || account.equals("EQUITY");
            jdbc.update("""
                INSERT INTO quarterly_version_fact(version_id,standard_account,raw_account_name,statement_division,
                  amount,cumulative_amount,currency,display_unit)
                VALUES (?,?,?,?,?,?,'KRW',1000000)
                """,version,account,account,balance?"BS":"IS",
                balance?(account.equals("ASSETS")?600000000L:300000000L):700000000L,
                balance?null:700000000L);
        }
        assertThatThrownBy(() -> periodic.find(company,2025,io.corpscope.financial.ReportType.Q1,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,5,15)))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.NO_PUBLIC_FILING));
        var q1=periodic.find(company,2025,io.corpscope.financial.ReportType.Q1,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,5,16));
        assertThat(q1.receiptNumber()).isEqualTo("20250515001922");
        assertThat(q1.accounts().stream().filter(a -> a.standardAccount().name().equals("REVENUE"))
            .findFirst().orElseThrow().amount()).isEqualByComparingTo("700000000");
        assertThat(periodic.find(company,2025,io.corpscope.financial.ReportType.Q3,
            io.corpscope.financial.StatementType.CFS,LocalDate.of(2025,11,17)).receiptNumber())
            .isEqualTo("20251114002447");
    }
}
