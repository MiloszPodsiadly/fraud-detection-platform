package com.frauddetection.scoring.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringKafkaErrorHandlerArchitectureGuardTest {

    @Test
    void rulesValidationFailuresUseTheExistingKafkaRetryAndDeadLetterPolicy() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/config/FraudScoringKafkaConfig.java"
        ));

        assertThat(source)
                .contains("new DefaultErrorHandler(deadLetterPublishingRecoverer, new FixedBackOff(retryBackoffMillis, retryAttempts))")
                .doesNotContain("RulesFeatureInputValidationException")
                .doesNotContain("addNotRetryableExceptions")
                .doesNotContain("setClassifications");
    }
}
