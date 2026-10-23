package io.corpscope.company;

import java.time.LocalDate;

public record CompanyResponse(Long id, String corpCode, String name, String stockCode,
        String corporateRegistrationNumber, String businessRegistrationNumber, String ceo,
        String industryCode, LocalDate establishedDate, String address, String homepage) {
    public static CompanyResponse from(Company c) {
        return new CompanyResponse(c.getId(), c.getCorpCode(), c.getName(), c.getStockCode(),
                c.getCorporateRegistrationNumber(), c.getBusinessRegistrationNumber(), c.getCeo(),
                c.getIndustryCode(), c.getEstablishedDate(), c.getAddress(), c.getHomepage());
    }
}
