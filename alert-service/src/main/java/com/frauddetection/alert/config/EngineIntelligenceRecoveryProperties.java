package com.frauddetection.alert.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.kafka.engine-intelligence-recovery")
public record EngineIntelligenceRecoveryProperties(
        @NotBlank String deadLetterTopic,
        @NotBlank String redriveTopic,
        @NotBlank String quarantineTopic,
        @NotBlank String redriveGroupId,
        @NotBlank String sourceConsumerGroup,
        boolean redriveEnabled
) {
}
