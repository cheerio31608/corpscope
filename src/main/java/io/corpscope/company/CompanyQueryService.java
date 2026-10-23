package io.corpscope.company;

import io.corpscope.common.*;
import io.corpscope.disclosure.*;
import io.corpscope.financial.*;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanyQueryService {
    private final CompanyRepository companies;
    private final FinancialFactRepository financials;
    private final DisclosureRepository disclosures;

    public CompanyQueryService(CompanyRepository companies, FinancialFactRepository financials,
                               DisclosureRepository disclosures) {
        this.companies = companies;
        this.financials = financials;
        this.disclosures = disclosures;
    }

    public CompanyResponse company(Long id) {
        return CompanyResponse.from(companies.findById(id)
                .orElseThrow(() -> new NotFoundException("Company not found: " + id)));
    }

    public PageResponse<CompanyResponse> search(String name, int page, int size) {
        String prefix = name.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return PageResponse.from(companies.searchByNamePrefix(prefix, PageRequest.of(page, size)).map(CompanyResponse::from));
    }

    public PageResponse<FinancialResponse> financials(Long id, Integer year, ReportType report,
            StatementType statement, int page, int size) {
        requireCompany(id);
        return PageResponse.from(financials.search(id, year, report, statement, PageRequest.of(page, size))
                .map(FinancialResponse::from));
    }

    public PageResponse<DisclosureResponse> disclosures(Long id, LocalDate from, LocalDate to, int page, int size) {
        requireCompany(id);
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Invalid date range");
        return PageResponse.from(disclosures.search(id, from, to, PageRequest.of(page, size)).map(DisclosureResponse::from));
    }

    private void requireCompany(Long id) {
        if (!companies.existsById(id)) throw new NotFoundException("Company not found: " + id);
    }
}
