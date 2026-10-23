package io.corpscope.external.dart;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DartCompanyResponse(
        String status, String message,
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("jurir_no") String corporateRegistrationNumber,
        @JsonProperty("bizr_no") String businessRegistrationNumber,
        @JsonProperty("ceo_nm") String ceo,
        @JsonProperty("induty_code") String industryCode,
        @JsonProperty("est_dt") String establishedDate,
        @JsonProperty("adres") String address,
        @JsonProperty("hm_url") String homepage) {}
