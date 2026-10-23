package io.corpscope.ingestion;

/** Controlled diagnostics; never include an external response or request URL. */
public class DataQualityException extends IllegalArgumentException {
    public enum Code {
        COMPANY_IDENTITY, COMPANY_FIELD, COMPANY_DATE, FINANCIAL_IDENTITY,
        AMOUNT_FORMAT, AMOUNT_RANGE, DUPLICATE_ACCOUNT, RESPONSE_STRUCTURE
    }

    private final Code code;

    public DataQualityException(Code code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public Code code() { return code; }
}
