package io.corpscope.operations;

import io.corpscope.external.dart.*;
import io.corpscope.ingestion.DartTransformer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact-receipt quarterly pilot: official list response is retained before any document review. */
@Service
public class QuarterlyPublicationCollectionService {
    private final DartGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public QuarterlyPublicationCollectionService(DartGateway gateway, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public Map<String, Object> collect(String corpCode, String receipt, LocalDate date) {
        if (!"OPENDART".equals(gateway.source())) throw new IllegalArgumentException("Real-data profile required");
        if (corpCode == null || !corpCode.matches("[0-9]{8}") || receipt == null || !receipt.matches("[0-9]{14}")
                || date == null || date.isBefore(LocalDate.of(2016, 1, 1)) || date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Exact company, receipt and receipt date required");
        }
        var ids = jdbc.queryForList("SELECT id FROM company WHERE corp_code=?", Long.class, corpCode);
        if (ids.size() != 1) throw new IllegalArgumentException("Collect the company before its quarterly publication");
        long companyId = ids.getFirst();
        List<ApiPayload<DartFilingsResponse>> pages = new ArrayList<>();
        DartFilingsResponse.Filing match = null;
        Set<String> seen = new HashSet<>();
        int totalPages = 1;
        int totalCount = -1;
        for (int page = 1; page <= totalPages; page++) {
            var payload = gateway.quarterlyFilings(corpCode, date, page);
            var data = payload.data();
            if (!"000".equals(data.status()) || data.pageNumber() != page || data.totalPages() < 1
                    || data.totalPages() > 100 || data.totalCount() < 1 || data.list() == null
                    || data.list().isEmpty() || data.list().size() > 100) throw new DartException("Invalid quarterly filing page");
            if (page == 1) { totalPages = data.totalPages(); totalCount = data.totalCount(); }
            if (totalPages != data.totalPages() || totalCount != data.totalCount())
                throw new DartException("Quarterly filing pages changed during collection");
            for (var filing : data.list()) {
                if (filing == null || !corpCode.equals(filing.corpCode()) || filing.receiptNumber() == null
                        || !filing.receiptNumber().matches("[0-9]{14}") || filing.reportTitle() == null
                        || filing.reportTitle().length() > 500 || !date.format(DateTimeFormatter.BASIC_ISO_DATE).equals(filing.receiptDate())
                        || !seen.add(filing.receiptNumber())) throw new DartException("Invalid quarterly filing identity");
                if (receipt.equals(filing.receiptNumber())) match = filing;
            }
            pages.add(payload);
        }
        if (seen.size() != totalCount || match == null || !match.reportTitle().contains("분기보고서"))
            throw new DartException("Exact quarterly receipt missing from complete official list");
        String title = match.reportTitle();
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM company WHERE id=? FOR UPDATE", Long.class, companyId);
            Long matchedRawId = null;
            for (int i = 0; i < pages.size(); i++) {
                var payload = pages.get(i);
                String requestKey = date.format(DateTimeFormatter.BASIC_ISO_DATE) + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + ":A003:N:" + (i + 1);
                Long rawId = jdbc.queryForObject("""
                        INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
                        VALUES (?,'list',?,?,CAST(? AS jsonb))
                        ON CONFLICT(company_id,endpoint,request_key,payload_hash)
                        DO UPDATE SET payload_hash=EXCLUDED.payload_hash RETURNING id
                        """, Long.class, companyId, requestKey, DartTransformer.sha256(payload.rawJson()), payload.rawJson());
                if (payload.data().list().stream().anyMatch(f -> receipt.equals(f.receiptNumber()))) matchedRawId = rawId;
            }
            var existing = jdbc.queryForList("SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?",
                    companyId, receipt);
            if (!existing.isEmpty() && (!date.equals(((java.sql.Date) existing.getFirst().get("receipt_date")).toLocalDate())
                    || !title.equals(existing.getFirst().get("report_title"))))
                throw new DartException("Previously verified quarterly metadata changed; manual review required");
            jdbc.update("""
                    INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,correction_marker,list_raw_ingestion_id)
                    VALUES (?,?,?,?,?,?) ON CONFLICT(company_id,receipt_number) DO NOTHING
                    """, companyId, receipt, date, title, title.startsWith("["), matchedRawId);
            return Map.<String, Object>of("corpCode", corpCode, "receiptNumber", receipt, "receiptDate", date,
                    "reportTitle", title, "pages", pages.size(), "status", "PUBLICATION_VERIFIED_ONLY");
        });
    }
}
