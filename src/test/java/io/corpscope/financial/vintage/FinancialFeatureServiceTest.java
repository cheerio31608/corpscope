package io.corpscope.financial.vintage;

import io.corpscope.analysis.*;
import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialFeatureServiceTest {
    final FinancialAsOfService query=mock(FinancialAsOfService.class);
    final FinancialPeriodComparisonService comparisons=mock(FinancialPeriodComparisonService.class);
    final LocalDate date=LocalDate.of(2018,1,1);

    @Test void calculatesSamePeriodMetricsAndNeverInventsPriorPeriodInputs() {
        stub("100","20","200","100","100");
        var result=service(Map.of(),Map.of()).create(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(result.metrics().operatingMargin()).isEqualByComparingTo("20.0000");
        assertThat(result.metrics().debtRatio()).isEqualByComparingTo("100.0000");
        assertThat(result.metrics().revenueGrowth()).isNull();
        assertThat(result.metrics().roa()).isNull();
        assertThat(result.metrics().roe()).isNull();
        assertThat(result.unavailableMetricReasons()).containsEntry("roe","PREVIOUS_PERIOD_BASIS_NOT_VERIFIED");
        assertThat(result.forecastReady()).isFalse();
        assertThat(result.evidence().receiptNumber()).isEqualTo("20170331004820");
        verify(query).find(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        verifyNoMoreInteractions(query);
    }

    @ParameterizedTest @CsvSource({"0,100,100,false,true","-100,100,100,false,true","100,200,0,true,false","100,210,-10,true,false"})
    void invalidDenominatorsAreNullNotZero(String revenue,String liabilities,String equity,boolean margin,boolean debt) {
        stub(revenue,"-20","200",liabilities,equity);
        var result=service(Map.of(),Map.of()).create(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(result.metrics().operatingMargin()!=null).isEqualTo(margin);
        assertThat(result.metrics().debtRatio()!=null).isEqualTo(debt);
        if(margin) assertThat(result.metrics().operatingMargin()).isNegative();
        if(!margin) assertThat(result.unavailableMetricReasons()).containsKey("operatingMargin");
        if(!debt) assertThat(result.unavailableMetricReasons()).containsKey("debtRatio");
    }

    @ParameterizedTest @ValueSource(strings={"company","period"})
    void existingAnalysisExclusionsSuppressEveryMetricButPreserveObservations(String type) {
        stub("100","20","200","100","100");
        var service=service(type.equals("company") ? Map.of("00164742","Reviewed company hold"):Map.of(),
            type.equals("period") ? Map.of("00164742:2016:ANNUAL:CFS","Reviewed period hold"):Map.of());
        var result=service.create(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(result.analysisAllowed()).isFalse();
        assertThat(result.exclusionReason()).contains("hold");
        assertThat(result.metrics()).isEqualTo(new FinancialMetricsCalculator.Metrics(null,null,null,null,null));
        assertThat(result.values().get(StandardAccount.REVENUE)).isEqualByComparingTo("100");
        assertThat(result.unavailableMetricReasons()).hasSize(5);
        assertThat(result.unavailableMetricReasons().values()).containsOnly("ANALYSIS_EXCLUDED");
    }

    @Test void exactScopeExclusionDoesNotExcludeSeparateStatement() {
        stub("100","20","200","100","100");
        var result=service(Map.of(),Map.of("00164742:2016:ANNUAL:OFS","OFS only"))
            .create(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        assertThat(result.analysisAllowed()).isTrue();
    }

    @Test void selectedVersionFailurePropagatesWithoutFallingBack() {
        var failure=new FinancialAsOfUnavailableException(FinancialAsOfUnavailableException.Reason.UNREVIEWED_SELECTED_FILING,"Not reviewed");
        when(query.find(1,2016,ReportType.ANNUAL,StatementType.CFS,date)).thenThrow(failure);
        assertThatThrownBy(() -> service(Map.of(),Map.of()).create(1,2016,ReportType.ANNUAL,StatementType.CFS,date)).isSameAs(failure);
        verify(query).find(1,2016,ReportType.ANNUAL,StatementType.CFS,date);
        verifyNoMoreInteractions(query);
    }

    private FinancialFeatureService service(Map<String,String> companies,Map<String,String> periods) {
        return new FinancialFeatureService(query,new FinancialMetricsCalculator(),new AnalysisEligibilityProperties(companies,periods),comparisons);
    }
    private void stub(String revenue,String op,String assets,String liabilities,String equity) {
        Map<StandardAccount,String> input=Map.of(StandardAccount.REVENUE,revenue,StandardAccount.OPERATING_PROFIT,op,
            StandardAccount.NET_INCOME,"10",StandardAccount.ASSETS,assets,StandardAccount.LIABILITIES,liabilities,StandardAccount.EQUITY,equity);
        var accounts=input.entrySet().stream().map(e -> new FinancialAsOfResponse.Account(e.getKey(),e.getKey().name(),
            Set.of(StandardAccount.ASSETS,StandardAccount.LIABILITIES,StandardAccount.EQUITY).contains(e.getKey()) ? "BS":"IS",
            new BigDecimal(e.getValue()),"KRW",1000000)).toList();
        when(query.find(1,2016,ReportType.ANNUAL,StatementType.CFS,date)).thenReturn(new FinancialAsOfResponse(
            1,"00164742",2016,ReportType.ANNUAL,StatementType.CFS,date,LocalDate.of(2026,10,13),
            "20170331004820",LocalDate.of(2017,3,31),LocalDate.of(2017,4,1),"Synthetic fixture","OPENDART_DOCUMENT",
            "0".repeat(64),Hyundai2016ReportParser.PROFILE,accounts,List.of("CROSS_PERIOD_COMPARABILITY_NOT_VERIFIED")));
    }
}
