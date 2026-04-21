package com.frauddetection.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicProperties(
        @NotBlank String transactionScored,
        @NotBlank String fraudAlerts,
        @NotBlank String fraudDecisions,
        @NotBlank String transactionsDeadLetter
) {
}
