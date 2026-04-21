package com.frauddetection.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(
        @NotNull AssistantMode mode,
        @NotBlank String ollamaBaseUrl,
        @NotBlank String ollamaModel,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    public AssistantProperties {
        if (mode == null) {
            mode = AssistantMode.DETERMINISTIC;
        }
        if (ollamaBaseUrl == null || ollamaBaseUrl.isBlank()) {
            ollamaBaseUrl = "http://localhost:11434";
        }
        if (ollamaModel == null || ollamaModel.isBlank()) {
            ollamaModel = "llama3.2:3b";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofMillis(500);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(45);
        }
    }
}
