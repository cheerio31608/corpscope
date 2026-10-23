package io.corpscope.financial.vintage;

import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** Read-only 2025 Q3 reported comparison; not part of the forecast feature export. */
@RestController
@RequestMapping("/api/companies/{id}/financials/periodic/reported-comparison")
public class PeriodicReportedComparisonController {
    private final PeriodicReportedComparisonService service;
    public PeriodicReportedComparisonController(PeriodicReportedComparisonService service) {
        this.service=service;
    }
    @GetMapping
    public PeriodicReportedComparisonService.Response find(@PathVariable @Positive long id,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.find(id,asOf);
    }
}
