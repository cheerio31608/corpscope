package io.corpscope.external.dart;

import io.corpscope.financial.FinancialPeriod;

public interface DartGateway {
    default String source() { return "OPENDART"; }
    ApiPayload<DartCompanyResponse> company(String corpCode);
    ApiPayload<DartFinancialResponse> financials(String corpCode, FinancialPeriod period);
    default byte[] document(String receiptNumber) {
        throw new UnsupportedOperationException("Document download is unavailable in demo mode");
    }
    default ApiPayload<DartFilingsResponse> annualFilings(String corpCode, java.time.LocalDate end, int page) {
        throw new UnsupportedOperationException("Filing metadata is unavailable in demo mode");
    }
    default ApiPayload<DartFilingsResponse> quarterlyFilings(String corpCode, java.time.LocalDate date, int page) {
        throw new UnsupportedOperationException("Quarterly filing metadata is unavailable in demo mode");
    }
    default ApiPayload<DartFilingsResponse> quarterlyFilings(String corpCode, java.time.LocalDate from,
                                                              java.time.LocalDate through, int page) {
        throw new UnsupportedOperationException("Quarterly filing coverage is unavailable in demo mode");
    }
    default ApiPayload<DartFilingsResponse> periodicFilings(String corpCode,
            io.corpscope.financial.ReportType reportType,java.time.LocalDate from,
            java.time.LocalDate through,int page) {
        throw new UnsupportedOperationException("Periodic filing coverage is unavailable in demo mode");
    }
}
