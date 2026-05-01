package com.frauddetection.alert.audit.external;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
class ExternalAuditAnchorHealthIndicator implements HealthIndicator {

    private final ExternalAuditAnchorSink sink;

    ExternalAuditAnchorHealthIndicator(ExternalAuditAnchorSink sink) {
        this.sink = sink;
    }

    @Override
    public Health health() {
        ExternalWitnessCapabilities capabilities = sink.capabilities() == null
                ? ExternalWitnessCapabilities.disabled()
                : sink.capabilities();
        String status = status(capabilities);
        return Health.up()
                .withDetail("external_anchor_status", status)
                .withDetail("witness_type", capabilities.witnessType())
                .withDetail("immutability_level", capabilities.immutabilityLevel())
                .withDetail("timestamp_trust_level", capabilities.timestampTrustLevel())
                .build();
    }

    private String status(ExternalWitnessCapabilities capabilities) {
        if (capabilities == null || "disabled".equals(capabilities.witnessType())) {
            return "DOWN";
        }
        if (capabilities.supportsReadAfterWrite()
                && capabilities.supportsStableReference()
                && capabilities.supportsWriteOnce()
                && capabilities.supportsDeleteDenialOrRetention()
                && capabilities.immutabilityLevel() == ExternalImmutabilityLevel.ENFORCED) {
            return "HEALTHY";
        }
        return "DEGRADED";
    }
}
