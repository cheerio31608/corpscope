package io.corpscope.operations;

import io.corpscope.external.dart.*;
import io.corpscope.ingestion.DartTransformer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit local operation. Network calls precede the per-company atomic metadata write. */
@Service
public class PublicationCollectionService {
    private final DartGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PublicationCollectionService(DartGateway gateway, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<Map<String, Object>> collect(String corpCode) {
        if (!"OPENDART".equals(gateway.source())) throw new IllegalArgumentException("Use the real-data profile for filing collection");
        if (corpCode != null && !corpCode.isBlank() && !corpCode.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("Invalid corpCode");
        }
        var companies = jdbc.queryForList("""
                SELECT DISTINCT c.id,c.corp_code FROM company c JOIN financial_fact f ON f.company_id=c.id
                WHERE f.source='OPENDART' AND (?='' OR c.corp_code=?) ORDER BY c.corp_code
                """, corpCode == null ? "" : corpCode, corpCode == null ? "" : corpCode);
        if (companies.isEmpty()) throw new IllegalArgumentException("No collected real company in scope");
        LocalDate end = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var company : companies) {
            long id = ((Number) company.get("id")).longValue();
            String code = (String) company.get("corp_code");
            result.add(collectCompany(id, code, end));
        }
        return List.copyOf(result);
    }

    Map<String, Object> collectCompany(long companyId, String corpCode, LocalDate end) {
        List<ApiPayload<DartFilingsResponse>> pages = new ArrayList<>();
        Set<String> receipts = new HashSet<>();
        int totalPages = 1;
        int totalCount = -1;
        for (int page = 1; page <= totalPages; page++) {
            var payload = gateway.annualFilings(corpCode, end, page);
            var data = payload.data();
            if ("013".equals(data.status())) {
                if (page != 1) throw new DartException("Filing pages changed during collection; retry the operation");
                if (data.list() != null && !data.list().isEmpty()) throw new DartException("No-data filing response contains records");
                pages.add(payload);
                break;
            }
            if (!"000".equals(data.status()) || data.pageNumber() != page
                    || data.totalPages() < 1 || data.totalPages() > 100
                    || data.totalCount() < 1 || data.list() == null || data.list().isEmpty()
                    || data.list().size() > 100) throw new DartException("Invalid filing pagination");
            if (page == 1) { totalPages = data.totalPages(); totalCount = data.totalCount(); }
            if (data.totalPages() != totalPages || data.totalCount() != totalCount) {
                throw new DartException("Filing pages changed during collection; retry the operation");
            }
            for (var filing : data.list()) {
                validate(filing, corpCode, end);
                if (!receipts.add(filing.receiptNumber())) throw new DartException("Duplicate receipt across filing pages");
            }
            pages.add(payload);
        }
        if (totalCount >= 0 && receipts.size() != totalCount) throw new DartException("Incomplete filing list");
        transaction.executeWithoutResult(status -> {
            // Same company lock as the financial writer; rollback all pages for this company on conflict.
            jdbc.queryForObject("SELECT id FROM company WHERE id=? AND corp_code=? FOR UPDATE", Long.class, companyId, corpCode);
            for (int i = 0; i < pages.size(); i++) {
                var payload = pages.get(i);
                String requestKey = "20160101:" + end.format(DateTimeFormatter.BASIC_ISO_DATE) + ":A001:N:" + (i + 1);
                Long rawId = jdbc.queryForObject("""
                        INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
                        VALUES (?,'list',?,?,CAST(? AS jsonb))
                        ON CONFLICT(company_id,endpoint,request_key,payload_hash)
                        DO UPDATE SET payload_hash=EXCLUDED.payload_hash RETURNING id
                        """, Long.class, companyId, requestKey, DartTransformer.sha256(payload.rawJson()), payload.rawJson());
                if (payload.data().list() == null) continue;
                for (var filing : payload.data().list()) {
                    LocalDate date = parseDate(filing.receiptDate());
                    boolean marker = filing.reportTitle().startsWith("[");
                    var existing = jdbc.queryForList("""
                            SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?
                            """, companyId, filing.receiptNumber());
                    if (!existing.isEmpty() && (!date.equals(((java.sql.Date) existing.getFirst().get("receipt_date")).toLocalDate())
                            || !filing.reportTitle().equals(existing.getFirst().get("report_title")))) {
                        throw new DartException("Previously verified filing metadata changed; manual review required");
                    }
                    jdbc.update("""
                            INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,
                              correction_marker,list_raw_ingestion_id) VALUES (?,?,?,?,?,?)
                            ON CONFLICT(company_id,receipt_number) DO NOTHING
                            """, companyId, filing.receiptNumber(), date, filing.reportTitle(), marker, rawId);
                }
            }
        });
        return Map.of("corpCode", corpCode, "pages", pages.size(), "verifiedFilings", receipts.size(), "searchedThrough", end);
    }

    private void validate(DartFilingsResponse.Filing filing, String code, LocalDate end) {
        if (filing == null || !code.equals(filing.corpCode()) || filing.receiptNumber() == null
                || !filing.receiptNumber().matches("[0-9]{14}") || filing.reportTitle() == null
                || !filing.reportTitle().contains("사업보고서") || filing.reportTitle().length() > 500) {
            throw new DartException("Invalid filing identity or annual report title");
        }
        LocalDate date = parseDate(filing.receiptDate());
        if (date.isBefore(LocalDate.of(2016, 1, 1)) || date.isAfter(end)) throw new DartException("Filing date outside requested scope");
    }

    private LocalDate parseDate(String date) {
        try {
            if (date == null || !date.matches("[0-9]{8}")) throw new IllegalArgumentException();
            return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException ex) { throw new DartException("Invalid filing date"); }
    }
}
