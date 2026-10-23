package io.corpscope.financial.vintage;

import io.corpscope.financial.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{id}/financials/periodic/as-of")
public class PeriodicAsOfController {
    private final PeriodicAsOfService service;
    public PeriodicAsOfController(PeriodicAsOfService service) {this.service=service;}
    @GetMapping
    public QuarterlyAsOfResponse find(@PathVariable @Positive long id,
            @RequestParam @Min(2016) @Max(2100) int fiscalYear,
            @RequestParam ReportType reportType,
            @RequestParam(defaultValue="CFS") StatementType statementType,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.find(id,fiscalYear,reportType,statementType,asOf);
    }
}
