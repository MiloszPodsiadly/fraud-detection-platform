package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.scoring.ml")
public record MlModelClientProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    public MlModelClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8090";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofMillis(500);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofMillis(1500);
        }
    }
}
