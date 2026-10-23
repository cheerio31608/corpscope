package io.corpscope.company;

import io.corpscope.common.NotFoundException;
import io.corpscope.disclosure.DisclosureRepository;
import io.corpscope.financial.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyQueryServiceTest {
    @Mock CompanyRepository companies;
    @Mock FinancialFactRepository financials;
    @Mock DisclosureRepository disclosures;
    CompanyQueryService service;

    @BeforeEach void setUp() { service = new CompanyQueryService(companies, financials, disclosures); }

    @Test void mapsCompanyToResponse() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(7L);
        when(company.getCorpCode()).thenReturn("00126380");
        when(company.getName()).thenReturn("Sample");
        when(companies.findById(7L)).thenReturn(Optional.of(company));
        assertThat(service.company(7L).corpCode()).isEqualTo("00126380");
    }

    @Test void missingCompanyDoesNotQueryFinancials() {
        when(companies.existsById(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.financials(9L, 2024, ReportType.ANNUAL, StatementType.CFS, 0, 20))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(financials);
    }
}
