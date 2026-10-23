package io.corpscope.operations;

import io.corpscope.common.NotFoundException;
import io.corpscope.external.dart.*;
import io.corpscope.financial.vintage.ReportVersionCollectionService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded raw-source preservation only. It never creates reviewed financial versions. */
@Service
public class ReportDocumentCollectionService {
    private final DartGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public ReportDocumentCollectionService(DartGateway gateway,JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.gateway=gateway; this.jdbc=jdbc; this.transaction=new TransactionTemplate(transactions);
    }

    public List<Document> collect(String corpCode,List<String> receipts) {
        if(corpCode==null || !corpCode.matches("[0-9]{8}") || receipts==null || receipts.isEmpty() || receipts.size()>5 ||
            receipts.stream().anyMatch(r -> r==null || !r.matches("[0-9]{14}")) || new HashSet<>(receipts).size()!=receipts.size())
            throw new IllegalArgumentException("Document collection requires company and 1..5 distinct receipts");
        if(!"OPENDART".equals(gateway.source())) throw new IllegalArgumentException("Use real-data profile for documents");
        var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
        if(ids.isEmpty()) throw new NotFoundException("Collect company and official filing metadata first");
        long company=ids.getFirst();
        // Reject unknown or other-company receipts before spending any API requests.
        for(String receipt:receipts) requirePublication(company,receipt);
        Map<String,byte[]> downloads=new LinkedHashMap<>();
        long total=0;
        for(String receipt:receipts) {
            byte[] zip=gateway.document(receipt);
            if(zip==null || zip.length<4 || zip.length>20*1024*1024 || zip[0]!='P' || zip[1]!='K')
                throw new DartException("Expected bounded official ZIP response");
            total+=zip.length;
            if(total>50L*1024*1024) throw new DartException("Requested report archives exceed the 50 MiB collection limit");
            downloads.put(receipt,zip);
        }
        // Network work is complete. All requested archives are now stored atomically under one company lock.
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM company WHERE id=? FOR UPDATE",Long.class,company);
            List<Document> result=new ArrayList<>();
            for(var item:downloads.entrySet()) {
                String receipt=item.getKey();
                requirePublication(company,receipt);
                byte[] zip=item.getValue();
                String hash=ReportVersionCollectionService.sha256(zip);
                var previous=jdbc.queryForList("SELECT sha256 FROM report_document WHERE company_id=? AND receipt_number=?",String.class,company,receipt);
                if(!previous.isEmpty() && !hash.equals(previous.getFirst()))
                    throw new IllegalArgumentException("Previously stored official ZIP changed; manual review required");
                jdbc.update("""
                    INSERT INTO report_document(company_id,receipt_number,sha256,zip_bytes) VALUES (?,?,?,?)
                    ON CONFLICT(company_id,receipt_number) DO NOTHING
                    """,company,receipt,hash,zip);
                result.add(new Document(corpCode,receipt,hash,zip.length,!previous.isEmpty(),false));
            }
            return List.copyOf(result);
        });
    }

    private void requirePublication(long company,String receipt) {
        var titles=jdbc.queryForList("SELECT report_title FROM report_publication WHERE company_id=? AND receipt_number=?",String.class,company,receipt);
        if(titles.size()!=1 || !(titles.getFirst().contains("사업보고서") || titles.getFirst().contains("분기보고서")
                || titles.getFirst().contains("반기보고서")))
            throw new IllegalArgumentException("Exact company/receipt has no verified periodic publication metadata");
    }
    public record Document(String corpCode,String receiptNumber,String sha256,int bytes,
            boolean alreadyStored,boolean financialReviewPerformed) {}
}
