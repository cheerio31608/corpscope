package io.corpscope.disclosure;

import java.time.LocalDate;

public record DisclosureResponse(Long id, String receiptNumber, String title, String disclosureType,
        LocalDate receiptDate, String reportCode, String originalUrl) {
    public static DisclosureResponse from(Disclosure d) {
        return new DisclosureResponse(d.getId(), d.getReceiptNumber(), d.getTitle(), d.getDisclosureType(),
                d.getReceiptDate(), d.getReportCode(), d.getOriginalUrl());
    }
}
