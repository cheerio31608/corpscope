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

/** Complete A003 search for Samsung's 2025 Q3 pilot through a bounded Q4 date. */
@Service
public class SamsungQ3CoverageService {
    public static final LocalDate FROM=LocalDate.of(2025,9,30);
    private final DartGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public SamsungQ3CoverageService(DartGateway gateway,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.gateway=gateway;this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);
    }
    public Map<String,Object> collect(LocalDate through) {
        if(!"OPENDART".equals(gateway.source()) || through==null || through.isBefore(LocalDate.of(2025,11,14))
            || through.isAfter(LocalDate.of(2025,12,31)) || through.isAfter(LocalDate.now()))
            throw new IllegalArgumentException("Real-data Samsung Q3 coverage requires a bounded 2025 Q4 through date");
        var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code='00126380'",Long.class);
        if(ids.size()!=1) throw new IllegalArgumentException("Collect Samsung company first");
        long company=ids.getFirst();
        List<ApiPayload<DartFilingsResponse>> pages=new ArrayList<>();
        Set<String> receipts=new HashSet<>();
        List<DartFilingsResponse.Filing> selected=new ArrayList<>();
        int totalPages=1,totalCount=-1;
        for(int page=1;page<=totalPages;page++) {
            var payload=gateway.quarterlyFilings("00126380",FROM,through,page);
            var data=payload.data();
            if(!"000".equals(data.status()) || data.pageNumber()!=page || data.totalPages()<1
                || data.totalPages()>100 || data.totalCount()<1 || data.list()==null || data.list().isEmpty()
                || data.list().size()>100) throw new DartException("Invalid quarterly coverage page");
            if(page==1) {totalPages=data.totalPages();totalCount=data.totalCount();}
            if(data.totalPages()!=totalPages || data.totalCount()!=totalCount)
                throw new DartException("Quarterly coverage pages changed during collection");
            for(var filing:data.list()) {
                if(filing==null || !"00126380".equals(filing.corpCode()) || filing.receiptNumber()==null
                    || !filing.receiptNumber().matches("[0-9]{14}") || filing.reportTitle()==null
                    || filing.reportTitle().length()>500 || filing.receiptDate()==null
                    || !filing.receiptDate().matches("[0-9]{8}") || !receipts.add(filing.receiptNumber()))
                    throw new DartException("Invalid quarterly coverage record");
                LocalDate date=LocalDate.parse(filing.receiptDate(),DateTimeFormatter.BASIC_ISO_DATE);
                if(date.isBefore(FROM) || date.isAfter(through)) throw new DartException("Quarterly coverage date outside request");
                if(filing.reportTitle().contains("분기보고서 (2025.09)")) selected.add(filing);
            }
            pages.add(payload);
        }
        if(receipts.size()!=totalCount || selected.stream().noneMatch(f ->
            f.receiptNumber().equals("20251114002447") && f.reportTitle().equals("분기보고서 (2025.09)")))
            throw new DartException("Incomplete Samsung Q3 coverage or missing original receipt");
        final int verifiedPages=totalPages, verifiedCount=totalCount;
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM company WHERE id=? FOR UPDATE",Long.class,company);
            for(int i=0;i<pages.size();i++) {
                var payload=pages.get(i);
                String key=FROM.format(DateTimeFormatter.BASIC_ISO_DATE)+":"+through.format(DateTimeFormatter.BASIC_ISO_DATE)+":A003:N:"+(i+1);
                Long rawId=jdbc.queryForObject("""
                    INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
                    VALUES (?,'list',?,?,CAST(? AS jsonb))
                    ON CONFLICT(company_id,endpoint,request_key,payload_hash)
                    DO UPDATE SET payload_hash=EXCLUDED.payload_hash RETURNING id
                    """,Long.class,company,key,DartTransformer.sha256(payload.rawJson()),payload.rawJson());
                for(var filing:payload.data().list()) {
                    if(!filing.reportTitle().contains("분기보고서 (2025.09)")) continue;
                    LocalDate date=LocalDate.parse(filing.receiptDate(),DateTimeFormatter.BASIC_ISO_DATE);
                    var existing=jdbc.queryForList("SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?",
                        company,filing.receiptNumber());
                    if(!existing.isEmpty() && (!date.equals(((java.sql.Date)existing.getFirst().get("receipt_date")).toLocalDate())
                        || !filing.reportTitle().equals(existing.getFirst().get("report_title"))))
                        throw new DartException("Previously verified quarterly metadata changed");
                    jdbc.update("""
                        INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,
                          correction_marker,list_raw_ingestion_id) VALUES (?,?,?,?,?,?)
                        ON CONFLICT(company_id,receipt_number) DO NOTHING
                        """,company,filing.receiptNumber(),date,filing.reportTitle(),filing.reportTitle().startsWith("["),rawId);
                }
            }
            int recorded=jdbc.update("""
                INSERT INTO quarterly_filing_coverage(company_id,checked_from,checked_through,total_pages,total_count)
                VALUES (?,?,?,?,?) ON CONFLICT(company_id,checked_from,checked_through)
                DO UPDATE SET verified_at=now()
                WHERE quarterly_filing_coverage.total_pages=EXCLUDED.total_pages
                  AND quarterly_filing_coverage.total_count=EXCLUDED.total_count
                """,company,FROM,through,verifiedPages,verifiedCount);
            if(recorded!=1) throw new DartException("Previously verified quarterly coverage count changed");
            return Map.<String,Object>of("status","COMPLETE_A003_SEARCH_NOT_MODEL_APPROVED","corpCode","00126380",
                "checkedFrom",FROM,"checkedThrough",through,"pages",verifiedPages,"totalFilings",verifiedCount,
                "q3PeriodCandidates",selected.size());
        });
    }
}
