package io.corpscope.financial;

public record FinancialPeriod(int fiscalYear, ReportType reportType, StatementType statementType) {
    public FinancialPeriod {
        if (fiscalYear < 2015 || fiscalYear > 2100 || reportType == null || statementType == null) {
            throw new IllegalArgumentException("Invalid financial period (2015..2100, reportType, statementType)");
        }
    }
}
