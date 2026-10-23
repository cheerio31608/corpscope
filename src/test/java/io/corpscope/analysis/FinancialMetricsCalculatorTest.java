package io.corpscope.analysis;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialMetricsCalculatorTest {
    private final FinancialMetricsCalculator calculator = new FinancialMetricsCalculator();
    private BigDecimal n(String value) { return new BigDecimal(value); }
    private FinancialMetricsCalculator.Inputs inputs(String revenue, String op, String net, String assets, String debt, String equity) {
        return new FinancialMetricsCalculator.Inputs(n(revenue), n(op), n(net), n(assets), n(debt), n(equity));
    }

    @Test void calculatesPercentagesUsingAverageAssetsAndEquity() {
        var result = calculator.calculate(inputs("1200", "240", "120", "2000", "800", "1200"),
                inputs("1000", "150", "100", "1800", "700", "1100"));
        assertThat(result.revenueGrowth()).isEqualByComparingTo("20.0000");
        assertThat(result.operatingMargin()).isEqualByComparingTo("20.0000");
        assertThat(result.debtRatio()).isEqualByComparingTo("66.6667");
        assertThat(result.roa()).isEqualByComparingTo("6.3158");
        assertThat(result.roe()).isEqualByComparingTo("10.4348");
    }

    @Test void preservesLossAndReturnsNullForInvalidDenominators() {
        var result = calculator.calculate(inputs("100", "-20", "-10", "200", "100", "-10"),
                inputs("0", "10", "10", "100", "0", "100"));
        assertThat(result.operatingMargin()).isEqualByComparingTo("-20");
        assertThat(result.roa()).isEqualByComparingTo("-6.6667");
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.debtRatio()).isNull();
        assertThat(result.roe()).isNull();
    }

    @Test void missingPriorValuesAreNotTreatedAsZero() {
        var result = calculator.calculate(inputs("100", "20", "10", "200", "100", "100"),
                new FinancialMetricsCalculator.Inputs(null, null, null, null, null, null));
        assertThat(result.revenueGrowth()).isNull();
        assertThat(result.roa()).isNull();
        assertThat(result.roe()).isNull();
        assertThat(result.debtRatio()).isEqualByComparingTo("100");
    }
}
