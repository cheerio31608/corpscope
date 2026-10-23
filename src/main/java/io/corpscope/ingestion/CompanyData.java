package io.corpscope.ingestion;

import java.time.LocalDate;

public record CompanyData(String corpCode, String name, String stockCode,
        String corporateRegistrationNumber, String businessRegistrationNumber,
        String ceo, String industryCode, LocalDate establishedDate, String address, String homepage) {}
