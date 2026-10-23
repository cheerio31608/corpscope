package io.corpscope.external.dart;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DartFilingsResponse(String status,
        @JsonProperty("page_no") int pageNumber,
        @JsonProperty("total_page") int totalPages,
        @JsonProperty("total_count") int totalCount, List<Filing> list) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filing(@JsonProperty("corp_code") String corpCode,
            @JsonProperty("rcept_no") String receiptNumber,
            @JsonProperty("rcept_dt") String receiptDate,
            @JsonProperty("report_nm") String reportTitle) {}
}
