package io.corpscope.operations;

import io.corpscope.dataset.LatestAnnualFinancialService;
import io.corpscope.financial.vintage.FinancialAsOfUnavailableException;
import io.corpscope.financial.vintage.FinancialFeatureResponse;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded export for a retrospective experiment; never approves training data. */
@Service
public class FinancialPanelExportService {
    public record Request(Map<String,List<LocalDate>> datesByStock) {}
    public record Row(String stockCode,LocalDate decisionDate,int fiscalYear,String receiptNumber,
        LocalDate availableFrom,Map<String,String> metrics) {}
    public record Withheld(String stockCode,LocalDate decisionDate,String reason) {}
    public record Result(String status,int requested,int exported,int withheldCount,List<Row> rows,
        List<Withheld> withheld,boolean forecastReady) {}

    private final JdbcTemplate jdbc;
    private final LatestAnnualFinancialService latest;
    public FinancialPanelExportService(JdbcTemplate jdbc,LatestAnnualFinancialService latest) {
        this.jdbc=jdbc;this.latest=latest;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result export(Request request) {
        if(request==null || request.datesByStock()==null || request.datesByStock().isEmpty() ||
            request.datesByStock().size()>20) throw new IllegalArgumentException("Invalid financial panel request");
        int requested=0;
        for(var entry:request.datesByStock().entrySet()) {
            var dates=entry.getValue();
            if(!entry.getKey().matches("[0-9]{6}") || dates==null || dates.isEmpty() ||
                dates.stream().anyMatch(Objects::isNull) || new HashSet<>(dates).size()!=dates.size())
                throw new IllegalArgumentException("Invalid stock or decision dates");
            requested+=dates.size();
        }
        if(requested>5000) throw new IllegalArgumentException("Financial panel exceeds 5000 decisions");
        List<Row> rows=new ArrayList<>();List<Withheld> withheld=new ArrayList<>();
        for(var stock:request.datesByStock().keySet().stream().sorted().toList()) {
            var companies=jdbc.queryForList("SELECT id FROM company WHERE stock_code=?",Long.class,stock);
            if(companies.size()!=1) {
                for(var date:request.datesByStock().get(stock)) withheld.add(new Withheld(stock,date,"COMPANY_NOT_UNIQUE_OR_MISSING"));
                continue;
            }
            for(var date:request.datesByStock().get(stock).stream().sorted().toList()) {
                try {
                    FinancialFeatureResponse feature=latest.latest(companies.getFirst(),date);
                    if(!feature.analysisAllowed()) {
                        withheld.add(new Withheld(stock,date,"ANALYSIS_EXCLUDED"));continue;
                    }
                    if(feature.evidence().availableFromKstDate().isAfter(date) ||
                        (feature.comparisonEvidence()!=null &&
                            (feature.comparisonEvidence().evidenceAvailableFromKstDate().isAfter(date) ||
                             feature.comparisonEvidence().previous().availableFromKstDate().isAfter(date))))
                        throw new IllegalStateException("Future financial disclosure in panel");
                    var m=feature.metrics();
                    Map<String,String> metrics=new LinkedHashMap<>();
                    metrics.put("revenueGrowth",value(m.revenueGrowth()));
                    metrics.put("operatingMargin",value(m.operatingMargin()));
                    metrics.put("debtRatio",value(m.debtRatio()));
                    metrics.put("roa",value(m.roa()));
                    metrics.put("roe",value(m.roe()));
                    rows.add(new Row(stock,date,feature.fiscalYear(),feature.evidence().receiptNumber(),
                        feature.evidence().availableFromKstDate(),metrics));
                } catch(FinancialAsOfUnavailableException ex) {
                    withheld.add(new Withheld(stock,date,ex.reason().name()));
                }
            }
        }
        return new Result("EXPLORATORY_BACKTEST_NOT_POINT_IN_TIME_APPROVED",requested,rows.size(),
            withheld.size(),List.copyOf(rows),List.copyOf(withheld),false);
    }
    private static String value(java.math.BigDecimal number) { return number==null ? null : number.toPlainString(); }
}
