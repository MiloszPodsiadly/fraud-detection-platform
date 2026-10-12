package com.frauddetection.alert.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class ExternalAuditHeadForensicService {

    private final AuditDegradationEventRepository repository;

    public ExternalAuditHeadForensicService(AuditDegradationEventRepository repository) {
        this.repository = repository;
    }

    public String recordIncident(
            String partitionKey,
            String observedVersion,
            String observedDigest,
            String reason,
            Instant detectedAt
    ) {
        String partition = normalize(partitionKey, 200, "UNKNOWN");
        String version = normalize(observedVersion, 200, null);
        String digest = normalize(observedDigest, 128, null);
        String normalizedReason = normalize(reason, 120, "HEAD_MANIFEST_INVALID");
        String incidentId = "external-head-" + sha256Hex(String.join(
                "|",
                partition,
                version == null ? "MISSING_VERSION" : version,
                digest == null ? "MISSING_DIGEST" : digest,
                normalizedReason
        ));

        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId(incidentId);
        event.setType(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY);
        event.setOperation("VERIFY_EXTERNAL_AUDIT_HEAD");
        event.setResourceType("AUDIT_EXTERNAL_ANCHOR");
        event.setResourceId(partition);
        event.setTimestamp(detectedAt == null ? Instant.now() : detectedAt);
        event.setResolved(false);
        event.setReason(normalizedReason);
        event.setObservedVersion(version);
        event.setObservedDigest(digest);
        event.setRecoveryAction("RECONSTRUCT_AND_CAS_HEAD");
        event.setRecoveryOutcome("DETECTED_PENDING_REPAIR");
        try {
            repository.insert(event);
        } catch (DuplicateKeyException ignored) {
            // Deterministic evidence identity makes repeated detection idempotent across restarts.
        } catch (DataAccessException exception) {
            throw new AuditPersistenceUnavailableException(
                    "External HEAD integrity evidence could not be persisted.",
                    exception
            );
        }
        return incidentId;
    }

    public void recordRepairOutcome(String incidentId, String outcome, Instant attemptedAt) {
        try {
            AuditDegradationEventDocument event = repository.findByAuditId(incidentId)
                    .orElseThrow(() -> new AuditPersistenceUnavailableException(
                            "External HEAD integrity evidence is unavailable.",
                            null
                    ));
            event.setRecoveryOutcome(normalize(outcome, 120, "REPAIR_FAILED"));
            event.setRecoveryAttemptedAt(attemptedAt == null ? Instant.now() : attemptedAt);
            event.setResolved("VERIFIED_REPAIRED".equals(event.getRecoveryOutcome()));
            repository.save(event);
        } catch (DataAccessException exception) {
            throw new AuditPersistenceUnavailableException(
                    "External HEAD repair evidence could not be persisted.",
                    exception
            );
        }
    }

    private String normalize(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Forensic incident identity could not be computed.", exception);
        }
    }
}
