package io.corpscope.operations;

import io.corpscope.external.dart.*;
import io.corpscope.financial.ReportType;
import io.corpscope.ingestion.DartTransformer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete, bounded A002/A003 search for one company and one exact fiscal period. */
@Service
public class PeriodicFilingCoverageService {
    private final DartGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public PeriodicFilingCoverageService(DartGateway gateway,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.gateway=gateway;this.jdbc=jdbc;this.transaction=new TransactionTemplate(manager);
    }
    public Map<String,Object> collect(String corpCode,int year,ReportType report,LocalDate through) {
        if(!"OPENDART".equals(gateway.source()) || corpCode==null || !corpCode.matches("[0-9]{8}")
            || year<2016 || year>2100 || report==null || report==ReportType.ANNUAL || through==null)
            throw new IllegalArgumentException("Exact real company and quarterly scope required");
        int month=switch(report) {case Q1 -> 3;case HALF_YEAR -> 6;case Q3 -> 9;default -> throw new IllegalArgumentException();};
        LocalDate from=LocalDate.of(year,month,1).withDayOfMonth(LocalDate.of(year,month,1).lengthOfMonth());
        // The extra window covers the next year's comparable filing and intervening corrections.
        // Still one company/period, a finite date range, and at most 100 pages.
        if(through.isBefore(from) || through.isAfter(LocalDate.now()) || ChronoUnit.DAYS.between(from,through)>550)
            throw new IllegalArgumentException("Periodic filing search must be bounded to 550 days after period end");
        String detail=report==ReportType.HALF_YEAR?"A002":"A003";
        String period=year+"."+String.format(Locale.ROOT,"%02d",month);
        String suffix=(report==ReportType.HALF_YEAR?"반기보고서 (":"분기보고서 (")+period+")";
        var ids=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
        if(ids.size()!=1) throw new IllegalArgumentException("Collect company before filing coverage");
        long company=ids.getFirst();
        List<ApiPayload<DartFilingsResponse>> pages=new ArrayList<>();
        Set<String> receipts=new HashSet<>();
        int totalPages=1,totalCount=-1,selectedCount=0;
        for(int page=1;page<=totalPages;page++) {
            var payload=gateway.periodicFilings(corpCode,report,from,through,page);
            var data=payload.data();
            if("013".equals(data.status()) && page==1 && (data.list()==null || data.list().isEmpty())) {
                pages.add(payload);totalPages=1;totalCount=0;break;
            }
            if(!"000".equals(data.status()) || data.pageNumber()!=page || data.totalPages()<1
                || data.totalPages()>100 || data.totalCount()<1 || data.list()==null || data.list().isEmpty()
                || data.list().size()>100) throw new DartException("Invalid periodic filing page");
            if(page==1) {totalPages=data.totalPages();totalCount=data.totalCount();}
            if(data.totalPages()!=totalPages || data.totalCount()!=totalCount)
                throw new DartException("Periodic filing pages changed during collection");
            for(var filing:data.list()) {
                if(filing==null || !corpCode.equals(filing.corpCode()) || filing.receiptNumber()==null
                    || !filing.receiptNumber().matches("[0-9]{14}") || filing.reportTitle()==null
                    || filing.reportTitle().length()>500 || filing.receiptDate()==null
                    || !filing.receiptDate().matches("[0-9]{8}") || !receipts.add(filing.receiptNumber()))
                    throw new DartException("Invalid periodic filing identity");
                LocalDate date=LocalDate.parse(filing.receiptDate(),DateTimeFormatter.BASIC_ISO_DATE);
                if(date.isBefore(from) || date.isAfter(through)) throw new DartException("Filing date outside search scope");
                if(filing.reportTitle().endsWith(suffix)) selectedCount++;
            }
            pages.add(payload);
        }
        if(receipts.size()!=totalCount) throw new DartException("Incomplete periodic filing list");
        final int verifiedPages=totalPages,verifiedCount=totalCount,matched=selectedCount;
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM company WHERE id=? FOR UPDATE",Long.class,company);
            for(int i=0;i<pages.size();i++) {
                var payload=pages.get(i);
                String key=from.format(DateTimeFormatter.BASIC_ISO_DATE)+":"+through.format(DateTimeFormatter.BASIC_ISO_DATE)
                    +":"+detail+":N:"+(i+1);
                Long rawId=jdbc.queryForObject("""
                    INSERT INTO raw_ingestion(company_id,endpoint,request_key,payload_hash,payload)
                    VALUES (?,'list',?,?,CAST(? AS jsonb))
                    ON CONFLICT(company_id,endpoint,request_key,payload_hash)
                    DO UPDATE SET payload_hash=EXCLUDED.payload_hash RETURNING id
                    """,Long.class,company,key,DartTransformer.sha256(payload.rawJson()),payload.rawJson());
                if(payload.data().list()==null) continue;
                for(var filing:payload.data().list()) {
                    if(!filing.reportTitle().endsWith(suffix)) continue;
                    LocalDate date=LocalDate.parse(filing.receiptDate(),DateTimeFormatter.BASIC_ISO_DATE);
                    var existing=jdbc.queryForList("SELECT receipt_date,report_title FROM report_publication WHERE company_id=? AND receipt_number=?",
                        company,filing.receiptNumber());
                    if(!existing.isEmpty() && (!date.equals(((java.sql.Date)existing.getFirst().get("receipt_date")).toLocalDate())
                        || !filing.reportTitle().equals(existing.getFirst().get("report_title"))))
                        throw new DartException("Previously verified periodic filing metadata changed");
                    jdbc.update("""
                        INSERT INTO report_publication(company_id,receipt_number,receipt_date,report_title,
                          correction_marker,list_raw_ingestion_id) VALUES (?,?,?,?,?,?)
                        ON CONFLICT(company_id,receipt_number) DO NOTHING
                        """,company,filing.receiptNumber(),date,filing.reportTitle(),filing.reportTitle().startsWith("["),rawId);
                }
            }
            int recorded=jdbc.update("""
                INSERT INTO periodic_filing_coverage(company_id,fiscal_year,report_type,detail_type,checked_from,
                  checked_through,total_pages,total_count) VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT(company_id,fiscal_year,report_type,checked_from,checked_through)
                DO UPDATE SET verified_at=now()
                WHERE periodic_filing_coverage.detail_type=EXCLUDED.detail_type
                  AND periodic_filing_coverage.total_pages=EXCLUDED.total_pages
                  AND periodic_filing_coverage.total_count=EXCLUDED.total_count
                """,company,year,report.name(),detail,from,through,verifiedPages,verifiedCount);
            if(recorded!=1) throw new DartException("Previously verified periodic coverage changed");
            return Map.<String,Object>of("status","COMPLETE_PERIODIC_LIST_NOT_MODEL_APPROVED","corpCode",corpCode,
                "fiscalYear",year,"reportType",report,"checkedFrom",from,"checkedThrough",through,
                "pages",verifiedPages,"totalFilings",verifiedCount,"periodCandidates",matched);
        });
    }
}
