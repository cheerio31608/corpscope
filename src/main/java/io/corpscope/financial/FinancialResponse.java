package io.corpscope.financial;

import java.math.BigDecimal;

public record FinancialResponse(Long id, int fiscalYear, ReportType reportType, StatementType statementType,
        String statementDivision, String accountCode, StandardAccount standardAccount, String rawAccountName,
        String accountDetail, BigDecimal amount, BigDecimal cumulativeAmount, String currency,
        String receiptNumber, String source) {
    public static FinancialResponse from(FinancialFact f) {
        return new FinancialResponse(f.getId(), f.getFiscalYear(), f.getReportType(), f.getStatementType(),
                f.getStatementDivision(), f.getAccountCode(), f.getStandardAccount(), f.getRawAccountName(),
                f.getAccountDetail(), f.getAmount(), f.getCumulativeAmount(), f.getCurrency(),
                f.getReceiptNumber(), f.getSource());
    }
}
