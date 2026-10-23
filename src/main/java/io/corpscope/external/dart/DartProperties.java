package io.corpscope.external.dart;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opendart")
public record DartProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    @Override public String toString() { return "DartProperties[apiKey=REDACTED]"; }
}
