package io.corpscope.operations;

import io.corpscope.financial.StatementType;
import io.corpscope.financial.vintage.FinancialFeatureResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FinancialFeatureExport(String schemaVersion,String status,Instant generatedAt,String corpCode,
        int fiscalYear,StatementType statementType,int requestedRows,int exportedRows,int withheldRows,
        List<FinancialFeatureResponse> rows,List<Withheld> withheld,boolean forecastReady,List<String> limitations) {
    public record Withheld(LocalDate asOf,String reason,String detail) {}
}
