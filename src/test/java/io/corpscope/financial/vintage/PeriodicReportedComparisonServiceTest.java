package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.ReportType;
import io.corpscope.financial.StatementType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PeriodicReportedComparisonServiceTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final PeriodicAsOfService periodic=mock(PeriodicAsOfService.class);
    private final PeriodicReportedComparisonService service=new PeriodicReportedComparisonService(periodic,mapper);

    @Test void allTenPinnedReportedPairsProduceOnlyAnUnapprovedGrowthObservation() throws Exception {
        try(var input=new ClassPathResource("periodic-q3-comparative-audit.json").getInputStream()) {
            var manifest=mapper.readValue(input,PeriodicReportedComparisonService.Manifest.class);
            assertThat(manifest.comparisons()).hasSize(10);
            for(var pair:manifest.comparisons()) {
                long id=Long.parseLong(pair.corpCode());
                LocalDate asOf=LocalDate.of(2025,11,17);
                when(periodic.find(id,2025,ReportType.Q3,StatementType.CFS,asOf))
                    .thenReturn(response(id,asOf,pair,true));
                when(periodic.find(id,2024,ReportType.Q3,StatementType.CFS,asOf))
                    .thenReturn(response(id,asOf,pair,false));
                var result=service.find(id,asOf);
                assertThat(result.reportedThreeMonthRevenueGrowthPercent()).isNotNull();
                assertThat(result.forecastReady()).isFalse();
                assertThat(result.status()).isEqualTo("REPORTED_THREE_MONTH_COMPARISON_NOT_MODEL_APPROVED");
                assertThat(result.warnings()).contains("EXCLUDED_FROM_FORECAST_FEATURE_EXPORT");
            }
        }
    }

    @Test void aLaterVersionCannotReuseThePinnedComparison() throws Exception {
        PeriodicReportedComparisonService.Pair pair;
        try(var input=new ClassPathResource("periodic-q3-comparative-audit.json").getInputStream()) {
            pair=mapper.readValue(input,PeriodicReportedComparisonService.Manifest.class).comparisons().getFirst();
        }
        long id=Long.parseLong(pair.corpCode());
        LocalDate asOf=LocalDate.of(2025,12,1);
        var reviewed=response(id,asOf,pair,true);
        var changed=new QuarterlyAsOfResponse(reviewed.companyId(),reviewed.corpCode(),reviewed.fiscalYear(),
            reviewed.reportType(),reviewed.statementType(),reviewed.asOf(),reviewed.knownFilingsCheckedThrough(),
            "20251201000001",reviewed.receiptDate(),reviewed.availableFromKstDate(),reviewed.reportTitle(),
            reviewed.documentSha256(),reviewed.extractionProfile(),reviewed.status(),reviewed.accounts(),reviewed.warnings());
        when(periodic.find(id,2025,ReportType.Q3,StatementType.CFS,asOf)).thenReturn(changed);
        when(periodic.find(id,2024,ReportType.Q3,StatementType.CFS,asOf))
            .thenReturn(response(id,asOf,pair,false));
        assertThatThrownBy(() -> service.find(id,asOf))
            .isInstanceOf(FinancialAsOfUnavailableException.class)
            .satisfies(ex -> assertThat(((FinancialAsOfUnavailableException)ex).reason())
                .isEqualTo(FinancialAsOfUnavailableException.Reason.COMPARISON_PAIR_NOT_VERIFIED));
    }

    private static QuarterlyAsOfResponse response(long id,LocalDate asOf,
            PeriodicReportedComparisonService.Pair pair,boolean current) {
        var accounts=pair.accounts().stream().map(a -> new QuarterlyAsOfResponse.Account(a.account(),
            a.account().name(),"IS",current?a.current2025ThreeMonth():a.originalThreeMonth(),
            current?a.current2025Ytd():a.originalYtd(),"KRW",
            current?a.comparativeDisplayUnit():a.originalDisplayUnit())).toList();
        return new QuarterlyAsOfResponse(id,pair.corpCode(),current?2025:2024,"Q3","CFS",asOf,
            LocalDate.of(2025,12,31),current?pair.currentReceipt():pair.priorReceipt(),
            LocalDate.of(current?2025:2024,11,14),LocalDate.of(current?2025:2024,11,15),
            "분기보고서",current?pair.currentZipSha256():pair.priorZipSha256(),"EXACT_SOURCE_REVIEW",
            "REVIEWED_SIX_TOTALS_NOT_MODEL_APPROVED",List.copyOf(accounts),List.of());
    }
}
