package io.corpscope.financial.vintage;

import io.corpscope.financial.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{id}/financials/as-of")
public class FinancialAsOfController {
    private final FinancialAsOfService service;
    public FinancialAsOfController(FinancialAsOfService service) { this.service=service; }
    @GetMapping
    public FinancialAsOfResponse find(@PathVariable @Positive long id,
            @RequestParam @Min(2015) @Max(2100) int fiscalYear,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue="ANNUAL") ReportType reportType,
            @RequestParam(defaultValue="CFS") StatementType statementType) {
        return service.find(id,fiscalYear,reportType,statementType,asOf);
    }
}
