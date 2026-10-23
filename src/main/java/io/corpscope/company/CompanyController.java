package io.corpscope.company;

import io.corpscope.analysis.*;
import io.corpscope.common.PageResponse;
import io.corpscope.disclosure.DisclosureResponse;
import io.corpscope.financial.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    private final CompanyQueryService queries;
    private final FinancialAnalysisService analysis;

    public CompanyController(CompanyQueryService queries, FinancialAnalysisService analysis) {
        this.queries = queries;
        this.analysis = analysis;
    }

    @GetMapping("/{id}")
    public CompanyResponse company(@PathVariable @Positive Long id) { return queries.company(id); }

    @GetMapping("/search")
    public PageResponse<CompanyResponse> search(@RequestParam @NotBlank @Size(max = 100) String name,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.search(name, page, size);
    }

    @GetMapping("/{id}/financials")
    public PageResponse<FinancialResponse> financials(@PathVariable @Positive Long id,
            @RequestParam(required = false) @Min(2015) @Max(2100) Integer fiscalYear,
            @RequestParam(required = false) ReportType reportType,
            @RequestParam(required = false) StatementType statementType,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.financials(id, fiscalYear, reportType, statementType, page, size);
    }

    @GetMapping("/{id}/disclosures")
    public PageResponse<DisclosureResponse> disclosures(@PathVariable @Positive Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.disclosures(id, from, to, page, size);
    }

    @GetMapping("/{id}/analysis")
    public CompanyFinancialAnalysisResponse analysis(@PathVariable @Positive Long id,
            @RequestParam @Min(2015) @Max(2100) int fiscalYear,
            @RequestParam(defaultValue = "ANNUAL") ReportType reportType,
            @RequestParam(defaultValue = "CFS") StatementType statementType) {
        return analysis.analyze(id, new FinancialPeriod(fiscalYear, reportType, statementType));
    }
}
