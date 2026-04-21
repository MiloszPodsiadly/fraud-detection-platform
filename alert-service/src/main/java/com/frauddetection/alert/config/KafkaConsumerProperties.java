package com.frauddetection.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@Validated
@ConfigurationProperties(prefix = "app.kafka.consumer")
public record KafkaConsumerProperties(
        @Positive Integer concurrency,
        @Positive Integer retryAttempts,
        @PositiveOrZero Long retryBackoffMillis
) {
}
