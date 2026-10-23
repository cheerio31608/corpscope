package io.corpscope.external.dart;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DartFinancialResponse(String status, String message, List<Account> list) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("bsns_year") String fiscalYear,
            @JsonProperty("reprt_code") String reportCode,
            @JsonProperty("rcept_no") String receiptNumber,
            @JsonProperty("sj_div") String statementDivision,
            @JsonProperty("account_id") String accountCode,
            @JsonProperty("account_nm") String accountName,
            @JsonProperty("account_detail") String accountDetail,
            @JsonProperty("thstrm_amount") String amount,
            @JsonProperty("thstrm_add_amount") String cumulativeAmount,
            String currency,
            @JsonProperty("ord") String ordinal) {}
}
