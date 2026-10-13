package com.frauddetection.alert.observability.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AuditIntegrityMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicLong chainHeadHashFingerprint = new AtomicLong(0);
    private final AtomicLong lastAnchorHashFingerprint = new AtomicLong(0);
    private final AtomicInteger integrityValid = new AtomicInteger(0);
    private final AtomicInteger integrityInvalid = new AtomicInteger(0);

    public AuditIntegrityMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("fraud_audit_chain_head_hash", chainHeadHashFingerprint, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_audit_last_anchor_hash", lastAnchorHashFingerprint, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("fraud_audit_integrity_status", integrityValid, AtomicInteger::get)
                .tag("status", "VALID")
                .register(meterRegistry);
        Gauge.builder("fraud_audit_integrity_status", integrityInvalid, AtomicInteger::get)
                .tag("status", "INVALID")
                .register(meterRegistry);
    }

    public void recordPlatformCheck(String status) {
        String normalizedStatus = normalizeIntegrityStatus(status);
        counter("fraud_platform_audit_integrity_checks_total", "status", normalizedStatus).increment();
        counter("fraud_platform_audit_integrity_check_total", "status", normalizedStatus).increment();
    }

    public void recordForensicCheck(String status) {
        counter("fraud_audit_integrity_check_total", "status", normalizeIntegrityStatus(status)).increment();
    }

    public void recordExternalCheck(String status) {
        counter(
                "fraud_platform_audit_external_integrity_checks_total",
                "status", normalizeIntegrityStatus(status)
        ).increment();
    }

    public void recordPlatformViolation(String violationType) {
        counter(
                "fraud_platform_audit_integrity_violations_total",
                "violation_type", normalizeIntegrityViolationType(violationType)
        ).increment();
    }

    public void recordForensicViolation(String violationType) {
        counter(
                "fraud_audit_integrity_violation_total",
                "violation_type", normalizeIntegrityViolationType(violationType)
        ).increment();
    }

    public void recordSnapshot(String status, String chainHeadHash, String lastAnchorHash) {
        chainHeadHashFingerprint.set(hashFingerprint(chainHeadHash));
        lastAnchorHashFingerprint.set(hashFingerprint(lastAnchorHash));
        boolean valid = "VALID".equals(status) || "PARTIAL".equals(status);
        integrityValid.set(valid ? 1 : 0);
        integrityInvalid.set("INVALID".equals(status) ? 1 : 0);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private String normalizeIntegrityStatus(String status) {
        if ("VALID".equals(status)
                || "INVALID".equals(status)
                || "PARTIAL".equals(status)
                || "UNAVAILABLE".equals(status)) {
            return status;
        }
        return "UNAVAILABLE";
    }

    private String normalizeIntegrityViolationType(String violationType) {
        return switch (violationType) {
            case "EVENT_HASH_MISMATCH",
                 "PREVIOUS_HASH_MISMATCH",
                 "INVALID_SCHEMA_VERSION",
                 "UNSUPPORTED_HASH_ALGORITHM",
                 "ANCHOR_MISSING",
                 "ANCHOR_HASH_MISMATCH",
                 "ANCHOR_CHAIN_POSITION_MISMATCH",
                 "MISSING_PREDECESSOR",
                 "CHAIN_FORK_DETECTED",
                 "CHAIN_POSITION_INVALID",
                 "CHAIN_POSITION_DUPLICATE",
                 "CHAIN_POSITION_GAP",
                 "EXTERNAL_ANCHOR_MISSING",
                 "STALE_EXTERNAL_ANCHOR",
                 "EXTERNAL_CHAIN_POSITION_AHEAD",
                 "EXTERNAL_HASH_MISMATCH",
                 "EXTERNAL_PAYLOAD_HASH_MISMATCH",
                 "EXTERNAL_OBJECT_KEY_MISMATCH",
                 "EXTERNAL_CHAIN_POSITION_MISMATCH",
                 "EXTERNAL_HASH_ALGORITHM_MISMATCH",
                 "EXTERNAL_SCHEMA_VERSION_UNSUPPORTED",
                 "EXTERNAL_LOCAL_ANCHOR_ID_MISMATCH",
                 "SIGNATURE_UNSIGNED",
                 "SIGNATURE_UNSIGNED_REQUIRED",
                 "SIGNATURE_UNAVAILABLE",
                 "SIGNATURE_UNAVAILABLE_REQUIRED",
                 "SIGNATURE_INVALID",
                 "SIGNATURE_UNKNOWN_KEY",
                 "SIGNATURE_KEY_REVOKED" -> violationType;
            default -> "UNKNOWN";
        };
    }

    private long hashFingerprint(String hash) {
        if (hash == null || hash.length() < 12) {
            return 0L;
        }
        try {
            byte[] bytes = HexFormat.of().parseHex(hash.substring(0, 12));
            long value = 0L;
            for (byte current : bytes) {
                value = (value << 8) | (current & 0xffL);
            }
            return value;
        } catch (IllegalArgumentException exception) {
            return 0L;
        }
    }
}
