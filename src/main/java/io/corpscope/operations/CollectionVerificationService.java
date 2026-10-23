package io.corpscope.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.analysis.FinancialAnalysisService;
import io.corpscope.analysis.CompanyFinancialAnalysisResponse;
import io.corpscope.common.NotFoundException;
import io.corpscope.external.dart.DartFinancialResponse;
import io.corpscope.financial.*;
import io.corpscope.ingestion.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionVerificationService {
    private final JdbcTemplate jdbc;
    private final FinancialFactRepository facts;
    private final DartTransformer transformer;
    private final ObjectMapper mapper;
    private final FinancialAnalysisService analysis;

    public CollectionVerificationService(JdbcTemplate jdbc, FinancialFactRepository facts,
            DartTransformer transformer, ObjectMapper mapper, FinancialAnalysisService analysis) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.transformer = transformer;
        this.mapper = mapper;
        this.analysis = analysis;
    }

    /** A consistent read snapshot; this command never fetches OpenDART or changes stored data. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CollectionVerificationReport verify(String corpCode, FinancialPeriod period) {
        if (corpCode == null || !corpCode.matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid corpCode");
        var ids = jdbc.queryForList("SELECT id FROM company WHERE corp_code=?", Long.class, corpCode);
        if (ids.isEmpty()) throw new NotFoundException("Company not collected");
        Long id = ids.getFirst();
        var rows = facts.findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(
                id, period.fiscalYear(), period.reportType(), period.statementType());
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (rows.isEmpty()) errors.add("No financial rows stored for the requested period");
        Set<Long> rawIds = rows.stream().map(FinancialFact::getRawIngestionId).collect(Collectors.toSet());
        if (rawIds.size() > 1) errors.add("Current period references multiple financial snapshots");
        Map<String, FinancialData> expected = new HashMap<>();
        for (Long rawId : rawIds) {
            var snapshots = jdbc.queryForList(
                    "SELECT endpoint,request_key,payload::text FROM raw_ingestion WHERE id=? AND company_id=?", rawId, id);
            if (snapshots.size() != 1) {
                errors.add("Missing original financial snapshot");
                continue;
            }
            var snapshot = snapshots.getFirst();
            String requestKey = period.fiscalYear() + ":" + period.reportType() + ":" + period.statementType();
            if (!"fnlttSinglAcntAll".equals(snapshot.get("endpoint")) || !requestKey.equals(snapshot.get("request_key"))) {
                errors.add("Original snapshot request scope mismatch");
                continue;
            }
            try {
                var response = mapper.readValue((String) snapshot.get("payload"), DartFinancialResponse.class);
                if (!"000".equals(response.status()) || response.list() == null || response.list().isEmpty()) {
                    errors.add("Original snapshot does not contain a successful account list");
                    continue;
                }
                for (var converted : transformer.financials(response.list(), corpCode, period)) {
                    if (expected.putIfAbsent(identity(converted.statementDivision(), converted.accountKey()), converted) != null) {
                        errors.add("Original snapshot contains duplicate account identities");
                    }
                }
            } catch (JsonProcessingException | IllegalArgumentException ex) {
                // Do not dump raw data or exception messages into reports.
                errors.add("Original snapshot cannot be normalized with the current rules");
            }
        }
        if (expected.size() != rows.size()) errors.add("Original and stored account counts differ");
        long mismatchCount = rows.stream().filter(row -> !matches(row,
                expected.get(identity(row.getStatementDivision(), row.getAccountKey())))).count();
        if (mismatchCount > 0) errors.add("Original-to-stored field mismatch in " + mismatchCount + " rows");
        boolean rawMatches = !rows.isEmpty() && errors.isEmpty();
        var currencies = rows.stream().map(FinancialFact::getCurrency).distinct().sorted().toList();
        var sources = rows.stream().map(FinancialFact::getSource).distinct().sorted().toList();
        if (currencies.size() > 1) warnings.add("Mixed currencies; review before analysis");
        if (sources.size() > 1) warnings.add("Mixed sources; review before publishing results");
        int unmapped = (int) rows.stream().filter(row -> row.getStandardAccount() == StandardAccount.UNMAPPED).count();
        int missing = (int) rows.stream().filter(row -> row.getAmount() == null).count();
        if (unmapped > 0) warnings.add("Unmapped accounts retained: " + unmapped);
        if (missing > 0) warnings.add("Missing current amounts retained as null: " + missing);
        if (period.reportType() != ReportType.ANNUAL) warnings.add("Quarterly amounts and cumulative amounts are stored separately; annual analysis skipped");
        var standardCounts = rows.stream().collect(Collectors.groupingBy(
                row -> row.getStandardAccount().name(), TreeMap::new, Collectors.counting()));
        CompanyFinancialAnalysisResponse metrics = null;
        if (!rows.isEmpty() && period.reportType() == ReportType.ANNUAL) {
            metrics = analysis.analyze(id, period);
            if (!metrics.warnings().isEmpty()) warnings.add("Some analysis metrics are unavailable; inspect analysis.warnings");
        }
        return new CollectionVerificationReport(errors.isEmpty() ? (warnings.isEmpty() ? "PASS" : "REVIEW") : "FAIL",
                corpCode, id, period, rows.size(), rawMatches, unmapped, missing, currencies, sources,
                standardCounts, rows.stream().filter(row -> row.getStandardAccount() == StandardAccount.UNMAPPED)
                        .map(FinancialFact::getRawAccountName).distinct().sorted().limit(20).toList(),
                List.copyOf(warnings), List.copyOf(errors), metrics);
    }

    private String identity(String division, String key) { return division + ":" + key; }

    private boolean matches(FinancialFact stored, FinancialData raw) {
        return raw != null && Objects.equals(stored.getAccountCode(), raw.accountCode())
                && stored.getStandardAccount() == raw.standardAccount()
                && Objects.equals(stored.getRawAccountName(), raw.rawAccountName())
                && Objects.equals(stored.getAccountDetail(), raw.accountDetail())
                && equalAmount(stored.getAmount(), raw.amount())
                && equalAmount(stored.getCumulativeAmount(), raw.cumulativeAmount())
                && Objects.equals(stored.getCurrency(), raw.currency())
                && Objects.equals(stored.getReceiptNumber(), raw.receiptNumber());
    }

    private boolean equalAmount(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? left == right : left.compareTo(right) == 0;
    }
}
