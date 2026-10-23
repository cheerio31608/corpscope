package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** An exact reviewed quarterly version; amount means BS balance or IS current three months. */
public record QuarterlyAsOfResponse(long companyId,String corpCode,int fiscalYear,String reportType,
        String statementType,LocalDate asOf,LocalDate knownFilingsCheckedThrough,String receiptNumber,
        LocalDate receiptDate,LocalDate availableFromKstDate,String reportTitle,String documentSha256,
        String extractionProfile,String status,List<Account> accounts,List<String> warnings) {
    public record Account(StandardAccount standardAccount,String rawAccountName,String statementDivision,
                          BigDecimal amount,BigDecimal cumulativeAmount,String currency,long displayUnit) {}
}
