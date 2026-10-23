package io.corpscope.batch;

import io.corpscope.external.dart.DartException;
import io.corpscope.ingestion.DataQualityException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.DataAccessException;

/** Failure categories for operators; raw exception messages/URLs are never exposed. */
public record BatchFailure(String category, String summary) {
    public static BatchFailure from(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof DataQualityException quality) {
                return new BatchFailure("DATA_QUALITY_" + quality.code(), "Review the source data and normalization rules");
            }
            if (current instanceof DartException.Transient) {
                return new BatchFailure("EXTERNAL_TRANSIENT", "Transient OpenDART failure; retry attempts exhausted");
            }
            if (current instanceof DartException dart) {
                if ("Set a valid OPENDART_API_KEY before collecting real data".equals(dart.getMessage())) {
                    return new BatchFailure("API_KEY_REQUIRED", "Set OPENDART_API_KEY locally, then restart with the same parameters");
                }
                String message = dart.getMessage() == null ? "" : dart.getMessage();
                var status = java.util.regex.Pattern.compile("^DART rejected request \\(status ([0-9]{3})\\)$").matcher(message);
                if (status.matches()) {
                    String code = status.group(1);
                    return new BatchFailure("DART_STATUS_" + code, "020".equals(code)
                            ? "Request quota exceeded; wait for quota recovery before restarting"
                            : "OpenDART rejected the request; consult the documented status code before restarting");
                }
                if (message.matches("DART HTTP [0-9]{3}")) {
                    return new BatchFailure("EXTERNAL_HTTP_" + message.substring(10), "Nonretryable HTTP response; check request configuration");
                }
                if ("Malformed DART response".equals(message)) {
                    return new BatchFailure("EXTERNAL_MALFORMED_RESPONSE", "OpenDART response was not valid JSON for the expected DTO");
                }
                return new BatchFailure("EXTERNAL_REJECTED", "Check OpenDART status, credentials and quota before restarting");
            }
            if (current instanceof DataAccessException) {
                return new BatchFailure("DATABASE", "Check DB availability and constraints; the failed chunk was rolled back");
            }
            if (current instanceof IllegalArgumentException) {
                return new BatchFailure("INVALID_INPUT", "Check identifying Job parameters and supported input data");
            }
        }
        return new BatchFailure("UNKNOWN", "Inspect local execution logs; no raw exception is included in this report");
    }
}
