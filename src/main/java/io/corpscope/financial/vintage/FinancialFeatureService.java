package io.corpscope.financial.vintage;

import io.corpscope.analysis.*;
import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinancialFeatureService {
    public static final String SCHEMA_VERSION="FINANCIAL_AS_OF_V2";
    private final FinancialAsOfService financials;
    private final FinancialMetricsCalculator calculator;
    private final AnalysisEligibilityProperties eligibility;
    private final FinancialPeriodComparisonService comparisons;

    public FinancialFeatureService(FinancialAsOfService financials,FinancialMetricsCalculator calculator,
            AnalysisEligibilityProperties eligibility,FinancialPeriodComparisonService comparisons) {
        this.financials=financials; this.calculator=calculator; this.eligibility=eligibility;
        this.comparisons=comparisons;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,noRollbackFor=FinancialAsOfUnavailableException.class)
    public FinancialFeatureResponse create(long company,int year,ReportType report,StatementType statement,LocalDate asOf) {
        var observed=financials.find(company,year,report,statement,asOf);
        Map<StandardAccount,BigDecimal> values=new EnumMap<>(StandardAccount.class);
        for(var account:observed.accounts()) values.put(account.standardAccount(),account.amount());
        String exclusion=eligibility.exclusionReasonFor(observed.corpCode(),year,report,statement);
        var empty=new FinancialMetricsCalculator.Inputs(null,null,null,null,null,null);
        var current=new FinancialMetricsCalculator.Inputs(values.get(StandardAccount.REVENUE),
            values.get(StandardAccount.OPERATING_PROFIT),values.get(StandardAccount.NET_INCOME),
            values.get(StandardAccount.ASSETS),values.get(StandardAccount.LIABILITIES),values.get(StandardAccount.EQUITY));
        // Never read latest financial_fact or the previous column of a subsequently corrected report.
        var comparison=exclusion==null ? comparisons.resolve(observed):Optional.<FinancialFeatureResponse.ComparisonEvidence>empty();
        var previous=comparison.map(c -> new FinancialMetricsCalculator.Inputs(c.previousValues().get(StandardAccount.REVENUE),
            c.previousValues().get(StandardAccount.OPERATING_PROFIT),c.previousValues().get(StandardAccount.NET_INCOME),
            c.previousValues().get(StandardAccount.ASSETS),c.previousValues().get(StandardAccount.LIABILITIES),c.previousValues().get(StandardAccount.EQUITY))).orElse(empty);
        var metrics=calculator.calculate(exclusion==null ? current:empty,previous);
        Map<String,String> reasons=new LinkedHashMap<>();
        if(exclusion!=null) {
            for(String metric:List.of("revenueGrowth","operatingMargin","debtRatio","roa","roe"))
                reasons.put(metric,"ANALYSIS_EXCLUDED");
        } else {
            if(comparison.isEmpty()) for(String metric:List.of("revenueGrowth","roa","roe")) reasons.put(metric,"PREVIOUS_PERIOD_BASIS_NOT_VERIFIED");
            else {
                if(metrics.revenueGrowth()==null) reasons.put("revenueGrowth","MISSING_VALUES_OR_NONPOSITIVE_PRIOR_REVENUE");
                if(metrics.roa()==null) reasons.put("roa","MISSING_VALUES_OR_NONPOSITIVE_BEGINNING_ENDING_ASSETS");
                if(metrics.roe()==null) reasons.put("roe","MISSING_VALUES_OR_NONPOSITIVE_BEGINNING_ENDING_EQUITY");
            }
            if(metrics.operatingMargin()==null) reasons.put("operatingMargin","MISSING_VALUES_OR_NONPOSITIVE_REVENUE");
            if(metrics.debtRatio()==null) reasons.put("debtRatio","MISSING_VALUES_OR_NONPOSITIVE_EQUITY");
        }
        var warnings=new ArrayList<>(observed.warnings());
        if(comparison.isPresent()) {
            warnings.remove("CROSS_PERIOD_COMPARABILITY_NOT_VERIFIED");
            warnings.add("REVIEWED_VERSION_PAIR_SIX_TOTALS_ONLY_NOT_ORGANIC_GROWTH");
            warnings.add("ROA_ROE_USE_TWO_YEAR_END_BALANCES_NOT_TIME_WEIGHTED_BALANCES");
        }
        // Official 2021 note 35: LINE disposal affects total profit and the reported balance base.
        // Keep reported amounts; never silently replace them with adjusted/continuing earnings.
        if(observed.corpCode().equals("00266961") && statement==StatementType.CFS) {
            if(year==2021) warnings.add("NET_INCOME_INCLUDES_LINE_DISCONTINUED_OPERATIONS_AND_DISPOSAL_GAIN");
            if(year==2021 || year==2022) warnings.add("REPORTED_BALANCES_INCLUDE_LINE_INTEGRATION_EFFECTS_NOT_ADJUSTED_RETURNS");
        }
        warnings.add("OBSERVED_FINANCIAL_FEATURES_ONLY_NO_PRICE_OR_TARGET");
        if(exclusion!=null) warnings.add("ANALYSIS_EXCLUDED");
        return new FinancialFeatureResponse(SCHEMA_VERSION,company,observed.corpCode(),year,report,statement,asOf,
            new FinancialFeatureResponse.Evidence(observed.receiptNumber(),observed.receiptDate(),observed.availableFromKstDate(),
                observed.documentSha256(),observed.extractionProfile(),observed.source(),"KRW"),
            Collections.unmodifiableMap(values),comparison.orElse(null),metrics,exclusion==null,exclusion,Map.copyOf(reasons),false,List.copyOf(warnings));
    }
}
