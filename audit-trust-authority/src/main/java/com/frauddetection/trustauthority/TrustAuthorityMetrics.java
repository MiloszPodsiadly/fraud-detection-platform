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
        Counter.builder("trust_sign_requests_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
    }

    void recordVerify(String status) {
        Counter.builder("trust_authority_verify_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
        Counter.builder("trust_verify_requests_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
    }

    void recordInvalidSignature() {
        Counter.builder("trust_authority_invalid_signature_total").register(registry).increment();
        Counter.builder("trust_invalid_signature_total").register(registry).increment();
    }

    void recordUnknownKey() {
        Counter.builder("trust_authority_unknown_key_total").register(registry).increment();
        Counter.builder("trust_unknown_key_total").register(registry).increment();
    }

    void recordRevokedKey() {
        Counter.builder("trust_authority_revoked_key_total").register(registry).increment();
    }

    void recordRateLimit() {
        Counter.builder("trust_authority_rate_limit_total").register(registry).increment();
        Counter.builder("trust_rate_limit_exceeded_total").register(registry).increment();
    }

    void recordReplayDetected() {
        Counter.builder("trust_replay_detected_total").register(registry).increment();
    }

    void recordJwtInvalid(String reason) {
        Counter.builder("trust_jwt_invalid_total")
                .tag("reason", boundedJwtReason(reason))
                .register(registry)
                .increment();
    }

    void recordJwtExpired() {
        Counter.builder("trust_jwt_expired_total").register(registry).increment();
    }

    void recordJwtKidMismatch() {
        Counter.builder("trust_jwt_kid_mismatch_total").register(registry).increment();
    }

    void recordJwtServiceMismatch() {
        Counter.builder("trust_jwt_service_mismatch_total").register(registry).increment();
    }

    void recordJwtAuthorityMissing() {
        Counter.builder("trust_jwt_authority_missing_total").register(registry).increment();
    }

    void recordAuditWrite(String status) {
        Counter.builder("trust_authority_audit_write_total")
                .tag("status", boundedStatus(status))
                .register(registry)
                .increment();
    }

    void recordAuditAppendRetry() {
        Counter.builder("audit_append_retry_total").register(registry).increment();
    }

    void recordAuditAppendConflict() {
        Counter.builder("audit_append_conflict_total").register(registry).increment();
        Counter.builder("trust_audit_append_conflict_total").register(registry).increment();
    }

    void recordAuditIntegrityResult(String status) {
        if ("PARTIAL".equals(status)) {
            Counter.builder("trust_audit_integrity_partial_total").register(registry).increment();
        } else if ("INVALID".equals(status)) {
            Counter.builder("trust_audit_integrity_invalid_total").register(registry).increment();
        }
    }

    private String boundedStatus(String status) {
        if ("SUCCESS".equals(status) || "FAILURE".equals(status)) {
            return status;
        }
        return "FAILURE";
    }

    private String boundedJwtReason(String reason) {
        if (reason == null) {
            return "invalid_claim";
        }
        return switch (reason) {
            case "INVALID_SIGNATURE" -> "invalid_signature";
            case "INVALID_CLAIM" -> "invalid_claim";
            case "UNAUTHORIZED_SERVICE" -> "unauthorized_service";
            case "EXPIRED" -> "expired";
            case "KID_MISMATCH" -> "kid_mismatch";
            case "AUTHORITY_MISSING" -> "authority_missing";
            default -> "invalid_claim";
        };
    }
}
