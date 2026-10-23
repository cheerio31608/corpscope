package io.corpscope.ingestion;

import io.corpscope.financial.StandardAccount;
import java.math.BigDecimal;

public record FinancialData(String statementDivision, String accountKey, String accountCode,
        StandardAccount standardAccount, String rawAccountName, String accountDetail,
        BigDecimal amount, BigDecimal cumulativeAmount, String currency, String receiptNumber) {}
