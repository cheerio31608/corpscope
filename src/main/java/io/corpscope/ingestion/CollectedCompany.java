package io.corpscope.ingestion;

import io.corpscope.financial.FinancialPeriod;
import java.util.List;

public record CollectedCompany(CompanyData company, FinancialPeriod period, List<FinancialData> financials,
        String companyJson, String financialJson, boolean noFinancialData, String source) {}
