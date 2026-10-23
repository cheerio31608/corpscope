package io.corpscope.ingestion;

import io.corpscope.external.dart.*;
import io.corpscope.financial.FinancialPeriod;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import io.corpscope.financial.StandardAccount;
import org.springframework.stereotype.Component;

@Component
public class DartTransformer {
    private final AccountStandardizer standardizer;
    public DartTransformer(AccountStandardizer standardizer) { this.standardizer = standardizer; }

    public CompanyData company(DartCompanyResponse raw, String requestedCode) {
        if (raw == null || requestedCode == null || !requestedCode.matches("[0-9]{8}")
                || !requestedCode.equals(raw.corpCode()) || blank(raw.corpName())) {
            throw new DataQualityException(DataQualityException.Code.COMPANY_IDENTITY, "Company identity mismatch or missing name");
        }
        checkLength(raw.corpName(), 300, "company name");
        checkDigits(raw.stockCode(), 6, "stock code");
        checkDigits(raw.corporateRegistrationNumber(), 13, "corporate registration number");
        checkDigits(raw.businessRegistrationNumber(), 10, "business registration number");
        checkLength(raw.ceo(), 300, "ceo");
        checkLength(raw.industryCode(), 10, "industry code");
        checkLength(raw.address(), 500, "address");
        checkLength(raw.homepage(), 500, "homepage");
        LocalDate date = null;
        if (!blank(raw.establishedDate())) {
            try {
                date = LocalDate.parse(raw.establishedDate().trim(), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (DateTimeParseException ex) {
                throw new DataQualityException(DataQualityException.Code.COMPANY_DATE, "Invalid establishment date");
            }
        }
        return new CompanyData(raw.corpCode(), raw.corpName().trim(), nullable(raw.stockCode()),
                nullable(raw.corporateRegistrationNumber()), nullable(raw.businessRegistrationNumber()),
                nullable(raw.ceo()), nullable(raw.industryCode()), date, nullable(raw.address()), nullable(raw.homepage()));
    }

    public FinancialData financial(DartFinancialResponse.Account raw, String corpCode, FinancialPeriod period) {
        if (raw == null || !corpCode.equals(raw.corpCode()) || !String.valueOf(period.fiscalYear()).equals(raw.fiscalYear())
                || !period.reportType().dartCode().equals(raw.reportCode())) {
            throw new DataQualityException(DataQualityException.Code.FINANCIAL_IDENTITY, "Financial period or company mismatch");
        }
        if (blank(raw.accountName()) || raw.statementDivision() == null
                || !Set.of("BS", "IS", "CIS", "CF", "SCE").contains(raw.statementDivision())
                || raw.receiptNumber() == null || !raw.receiptNumber().matches("[0-9]{14}")
                || raw.currency() == null || !raw.currency().matches("[A-Z]{3}")) {
            throw new DataQualityException(DataQualityException.Code.FINANCIAL_IDENTITY, "Missing or invalid financial identity fields");
        }
        String code = nullable(raw.accountCode());
        if (raw.accountName().trim().length() > 500 || (code != null && code.length() > 300)) {
            throw new DataQualityException(DataQualityException.Code.FINANCIAL_IDENTITY, "Account label exceeds database length");
        }
        String detail = raw.accountDetail() == null ? "" : raw.accountDetail().trim();
        String name = raw.accountName().trim();
        // Nonstandard IDs are shared by many unrelated rows. Name and SCE dimension prevent collision.
        boolean standardCode = code != null && !"-".equals(code) && !code.contains("표준계정코드");
        String identity = standardCode ? code : name;
        String key = sha256(identity.length() + ":" + identity + ":" + detail);
        return new FinancialData(raw.statementDivision(), key, code,
                standardizer.map(code, name, raw.statementDivision()), name, detail,
                amount(raw.amount()), amount(raw.cumulativeAmount()), raw.currency(), raw.receiptNumber());
    }

    /** Ordinal disambiguates repeated custom labels within one snapshot, not across reports. */
    public List<FinancialData> financials(List<DartFinancialResponse.Account> raw, String corpCode, FinancialPeriod period) {
        var converted = raw.stream().map(row -> financial(row, corpCode, period)).toList();
        var counts = new HashMap<String, Integer>();
        converted.forEach(row -> counts.merge(row.statementDivision() + ":" + row.accountKey(), 1, Integer::sum));
        var keys = new HashSet<String>();
        var result = new ArrayList<FinancialData>();
        for (int i = 0; i < converted.size(); i++) {
            var row = converted.get(i);
            String identity = row.statementDivision() + ":" + row.accountKey();
            if (counts.get(identity) > 1) {
                String code = row.accountCode();
                String ordinal = raw.get(i).ordinal();
                boolean custom = code == null || "-".equals(code) || code.contains("표준계정코드");
                if (!custom || ordinal == null || !ordinal.matches("[0-9]{1,10}")) {
                    throw duplicateAccount();
                }
                // Store each row without assigning a financial meaning to ambiguous custom labels.
                row = new FinancialData(row.statementDivision(), sha256("ordinal:" + row.accountKey() + ":" + ordinal),
                        row.accountCode(), StandardAccount.UNMAPPED, row.rawAccountName(), row.accountDetail(),
                        row.amount(), row.cumulativeAmount(), row.currency(), row.receiptNumber());
            }
            if (!keys.add(row.statementDivision() + ":" + row.accountKey())) throw duplicateAccount();
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static DataQualityException duplicateAccount() {
        return new DataQualityException(DataQualityException.Code.DUPLICATE_ACCOUNT, "Duplicate financial account identity");
    }

    public static BigDecimal amount(String value) {
        if (blank(value) || "-".equals(value.trim())) return null;
        String cleaned = value.trim();
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            String inside = cleaned.substring(1, cleaned.length() - 1);
            if (inside.startsWith("-") || inside.startsWith("+")) {
                throw new DataQualityException(DataQualityException.Code.AMOUNT_FORMAT, "Signed amount inside parentheses");
            }
            cleaned = "-" + inside;
        }
        if (!cleaned.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) {
            throw new DataQualityException(DataQualityException.Code.AMOUNT_FORMAT, "Invalid amount format");
        }
        BigDecimal amount = new BigDecimal(cleaned.replace(",", ""));
        if (amount.scale() > 2 || amount.precision() - amount.scale() > 36) {
            throw new DataQualityException(DataQualityException.Code.AMOUNT_RANGE, "Amount exceeds NUMERIC(38,2)");
        }
        return amount;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullable(String value) { return blank(value) ? null : value.trim(); }

    private static void checkLength(String value, int maximum, String field) {
        if (value != null && value.trim().length() > maximum) {
            throw new DataQualityException(DataQualityException.Code.COMPANY_FIELD, "Invalid length for " + field);
        }
    }

    private static void checkDigits(String value, int length, String field) {
        if (!blank(value) && !value.trim().matches("[0-9]{" + length + "}")) {
            throw new DataQualityException(DataQualityException.Code.COMPANY_FIELD, "Invalid " + field);
        }
    }
}
