package io.corpscope.financial.vintage;

import io.corpscope.financial.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{id}/financials/as-of/features")
public class FinancialFeatureController {
    private final FinancialFeatureService service;
    public FinancialFeatureController(FinancialFeatureService service) { this.service=service; }
    @GetMapping
    public FinancialFeatureResponse find(@PathVariable @Positive long id,
            @RequestParam @Min(2015) @Max(2100) int fiscalYear,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue="ANNUAL") ReportType reportType,
            @RequestParam(defaultValue="CFS") StatementType statementType) {
        return service.create(id,fiscalYear,reportType,statementType,asOf);
    }
}
