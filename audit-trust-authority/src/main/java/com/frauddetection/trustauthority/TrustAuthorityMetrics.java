package com.frauddetection.trustauthority;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class TrustAuthorityMetrics {

    private final MeterRegistry registry;

    TrustAuthorityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordSign(String status) {
        Counter.builder("trust_authority_sign_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
    }

    void recordVerify(String status) {
        Counter.builder("trust_authority_verify_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
    }

    void recordInvalidSignature() {
        Counter.builder("trust_authority_invalid_signature_total").register(registry).increment();
    }

    void recordUnknownKey() {
        Counter.builder("trust_authority_unknown_key_total").register(registry).increment();
    }

    void recordRevokedKey() {
        Counter.builder("trust_authority_revoked_key_total").register(registry).increment();
    }

    void recordRateLimit() {
        Counter.builder("trust_authority_rate_limit_total").register(registry).increment();
    }

    private String boundedStatus(String status) {
        if ("SUCCESS".equals(status) || "FAILURE".equals(status)) {
            return status;
        }
        return "FAILURE";
    }
}
