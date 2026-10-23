package io.corpscope.financial.vintage;

import io.corpscope.financial.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FinancialAsOfResponse(long companyId,String corpCode,int fiscalYear,ReportType reportType,
        StatementType statementType,LocalDate asOf,LocalDate knownFilingsCheckedThrough,
        String receiptNumber,LocalDate receiptDate,LocalDate availableFromKstDate,String reportTitle,
        String source,String documentSha256,String extractionProfile,List<Account> accounts,List<String> warnings) {
    public record Account(StandardAccount standardAccount,String rawAccountName,String statementDivision,
                          BigDecimal amount,String currency,long displayUnit) {}
}
