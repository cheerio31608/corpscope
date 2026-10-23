package io.corpscope.operations;

import io.corpscope.common.NotFoundException;
import io.corpscope.financial.*;
import io.corpscope.financial.vintage.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialFeatureExportService {
    private final JdbcTemplate jdbc;
    private final FinancialFeatureService features;
    public FinancialFeatureExportService(JdbcTemplate jdbc,FinancialFeatureService features) {
        this.jdbc=jdbc; this.features=features;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FinancialFeatureExport export(String corpCode,int year,StatementType statement,List<LocalDate> dates) {
        if(corpCode==null || !corpCode.matches("[0-9]{8}") || year<2015 || year>2100 || statement==null ||
            dates==null || dates.isEmpty() || dates.size()>100 || dates.stream().anyMatch(Objects::isNull) || new HashSet<>(dates).size()!=dates.size())
            throw new IllegalArgumentException("Export requires company, annual period and 1..100 distinct dates");
        var companies=jdbc.queryForList("SELECT id FROM company WHERE corp_code=?",Long.class,corpCode);
        if(companies.isEmpty()) throw new NotFoundException("Company not found");
        List<FinancialFeatureResponse> rows=new ArrayList<>();
        List<FinancialFeatureExport.Withheld> withheld=new ArrayList<>();
        for(LocalDate date:dates.stream().sorted().toList()) {
            try {
                var feature=features.create(companies.getFirst(),year,ReportType.ANNUAL,statement,date);
                if(feature.analysisAllowed()) rows.add(feature);
                else withheld.add(new FinancialFeatureExport.Withheld(date,"ANALYSIS_EXCLUDED",feature.exclusionReason()));
            } catch(FinancialAsOfUnavailableException ex) {
                withheld.add(new FinancialFeatureExport.Withheld(date,ex.reason().name(),ex.getMessage()));
            }
        }
        String status=rows.isEmpty() ? "WITHHELD":withheld.isEmpty() ? "COMPLETE":"PARTIAL";
        return new FinancialFeatureExport(FinancialFeatureService.SCHEMA_VERSION,status,Instant.now(),corpCode,year,statement,
            dates.size(),rows.size(),withheld.size(),List.copyOf(rows),List.copyOf(withheld),false,List.of(
                "Financial observations only: no prices, labels, train/test split or forecast-readiness guarantee",
                "A row repeats an annual observation at a requested date; repeated dates do not create independent financial periods",
                "Use only documented numeric values/metrics as candidate features, not administrative metadata or evidence identifiers",
                "Missing cross-period metrics remain null; withheld requests are explicit, never zero-filled"));
    }
}
