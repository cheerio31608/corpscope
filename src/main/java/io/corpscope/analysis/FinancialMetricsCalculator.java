package io.corpscope.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class FinancialMetricsCalculator {
    public record Inputs(BigDecimal revenue, BigDecimal operatingProfit, BigDecimal netIncome,
            BigDecimal assets, BigDecimal liabilities, BigDecimal equity) {}
    public record Metrics(BigDecimal revenueGrowth, BigDecimal operatingMargin, BigDecimal debtRatio,
            BigDecimal roa, BigDecimal roe) {}

    /** Percent units, annual profit / average beginning-and-ending assets or equity. */
    public Metrics calculate(Inputs current, Inputs previous) {
        return new Metrics(growth(current.revenue(), previous.revenue()),
                percent(current.operatingProfit(), current.revenue()),
                percent(current.liabilities(), current.equity()),
                percent(current.netIncome(), average(current.assets(), previous.assets())),
                percent(current.netIncome(), average(current.equity(), previous.equity())));
    }

    private BigDecimal growth(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null ? null : percent(current.subtract(previous), previous);
    }

    private BigDecimal average(BigDecimal ending, BigDecimal beginning) {
        if (ending == null || beginning == null || ending.signum() <= 0 || beginning.signum() <= 0) return null;
        return ending.add(beginning).divide(BigDecimal.valueOf(2));
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) return null;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP);
    }
}
