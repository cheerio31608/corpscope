package io.corpscope.financial.vintage;

import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{id}/financials/quarterly-pilot/as-of")
public class SamsungQ3AsOfController {
    private final SamsungQ3AsOfService service;
    public SamsungQ3AsOfController(SamsungQ3AsOfService service) {this.service=service;}
    @GetMapping
    public QuarterlyAsOfResponse find(@PathVariable @Positive long id,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.find(id,asOf);
    }
}
