package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessAuthStateClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessMethodClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessRouteClassifier;
import com.frauddetection.alert.security.telemetry.SecurityDeniedAccessTelemetryRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class SecurityDeniedAccessTelemetrySliceTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    SecurityDeniedAccessTelemetryRecorder securityDeniedAccessTelemetryRecorder(MeterRegistry meterRegistry) {
        return new SecurityDeniedAccessTelemetryRecorder(meterRegistry);
    }

    @Bean
    SecurityDeniedAccessRouteClassifier securityDeniedAccessRouteClassifier() {
        return new SecurityDeniedAccessRouteClassifier();
    }

    @Bean
    SecurityDeniedAccessMethodClassifier securityDeniedAccessMethodClassifier() {
        return new SecurityDeniedAccessMethodClassifier();
    }

    @Bean
    SecurityDeniedAccessAuthStateClassifier securityDeniedAccessAuthStateClassifier() {
        return new SecurityDeniedAccessAuthStateClassifier();
    }
}
