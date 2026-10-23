package io.corpscope.operations;

import io.corpscope.analysis.FinancialMetricsCalculator;
import io.corpscope.dataset.LatestAnnualFinancialService;
import io.corpscope.financial.ReportType;
import io.corpscope.financial.StatementType;
import io.corpscope.financial.vintage.FinancialFeatureResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialPanelExportServiceTest {
    @Test
    void refusesFutureEvidenceRatherThanExportingIt() {
        var jdbc=mock(JdbcTemplate.class);
        var latest=mock(LatestAnnualFinancialService.class);
        var date=LocalDate.of(2025,4,1);
        when(jdbc.queryForList(eq("SELECT id FROM company WHERE stock_code=?"),eq(Long.class),eq("005930")))
            .thenReturn(List.of(3L));
        var evidence=new FinancialFeatureResponse.Evidence("20250402000001",date.plusDays(1),date.plusDays(1),
            "sha","profile","OPENDART","KRW");
        var metrics=new FinancialMetricsCalculator.Metrics(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,
            BigDecimal.ONE,BigDecimal.ONE);
        when(latest.latest(3L,date)).thenReturn(new FinancialFeatureResponse("FINANCIAL_AS_OF_V2",3L,"00126380",
            2024,ReportType.ANNUAL,StatementType.CFS,date,evidence,Map.of(),null,metrics,
            true,null,Map.of(),false,List.of()));
        assertThatThrownBy(() -> new FinancialPanelExportService(jdbc,latest)
            .export(new FinancialPanelExportService.Request(Map.of("005930",List.of(date)))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Future financial disclosure");
    }
}
