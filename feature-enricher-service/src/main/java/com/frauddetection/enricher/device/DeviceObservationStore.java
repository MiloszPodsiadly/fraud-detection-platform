package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Internal persistence seam only. This type does not authenticate observations or coverage seals. */
interface DeviceObservationStore {
    Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    StageResult stage(Observation input);

    void seal(CoverageSeal seal);

    CommitResult commit(String institutionToken, String sourceCode, String sourceEventKey);

    PendingMetrics pendingMetrics();

    /**
     * Revokes one device/key-version from operational history for the bounded tombstone lifetime.
     *
     * <p>This removes active observation membership and quarantines staged work, but deliberately retains
     * terminal candidates and occurrence/evidence receipts until their independently bounded retention expires.
     * It is not a legal or complete data-erasure workflow.</p>
     */
    void revokeDeviceFromOperationalHistory(String institutionToken, String sourceCode, int keyVersion,
                                            String deviceToken);

    enum StageResult { CREATED, DUPLICATE }
    /** EXISTING means a verified replay with its committed candidate and occurrence both still present. */
    enum CommitStatus { COMMITTED, EXISTING, WAITING_FOR_SEAL, WAITING_FOR_EARLIER_EVENT, QUARANTINED }

    /** Aggregate only: deliberately contains no institution, source, event, customer, or device identity. */
    record PendingMetrics(long stagedCandidates, Duration oldestPendingAge) {
        public PendingMetrics {
            if (stagedCandidates < 0
                    || (stagedCandidates == 0) != (oldestPendingAge == null)
                    || oldestPendingAge != null && oldestPendingAge.isNegative()) {
                throw new IllegalArgumentException("DEVICE_STORE_PENDING_METRICS");
            }
        }
    }

    record CommitResult(CommitStatus status, DeviceRiskEvidence evidence) {
        public CommitResult {
            Objects.requireNonNull(status, "status");
            if ((status == CommitStatus.COMMITTED || status == CommitStatus.EXISTING) != (evidence != null)) {
                throw new IllegalArgumentException("DEVICE_STORE_RESULT_STATE");
            }
        }
    }

    record Observation(String institutionToken, String sourceCode, String sourceEventKey,
                       String transactionKey, String customerToken, String deviceToken,
                       int keyVersion, long sourceSequence, Instant eventTime, Instant sourceObservedAt,
                       String payloadCommitment, DeviceRiskEvidence.SourceQuality sourceQuality, Provenance provenance) {
        public Observation {
            institutionToken = token(institutionToken);
            sourceCode = code(sourceCode);
            sourceEventKey = token(sourceEventKey);
            transactionKey = token(transactionKey);
            customerToken = token(customerToken);
            deviceToken = token(deviceToken);
            payloadCommitment = token(payloadCommitment);
            Objects.requireNonNull(provenance, "provenance");
            if (keyVersion < 1 || keyVersion > 65_535 || sourceSequence < provenance.sequenceOrigin()
                    || sourceQuality != DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION) {
                throw new IllegalArgumentException("DEVICE_STORE_UNTRUSTED_INPUT");
            }
            Objects.requireNonNull(eventTime, "eventTime");
            Objects.requireNonNull(sourceObservedAt, "sourceObservedAt");
            if (eventTime.getNano() % 1_000_000 != 0 || sourceObservedAt.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("DEVICE_STORE_TIME_PRECISION");
            }
            if (sourceObservedAt.isBefore(eventTime.minusSeconds(300))
                    || sourceObservedAt.isAfter(eventTime.plusSeconds(120))) {
                throw new IllegalArgumentException("DEVICE_STORE_EVENT_TIME");
            }
        }
    }

    record CoverageSeal(String institutionToken, String sourceCode, long throughSequence,
                        Instant coverageStart, Instant watermark, int keyVersion, CoverageProof proof) {
        public CoverageSeal {
            institutionToken = token(institutionToken);
            sourceCode = code(sourceCode);
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(coverageStart, "coverageStart");
            Objects.requireNonNull(watermark, "watermark");
            if (coverageStart.getNano() % 1_000_000 != 0 || watermark.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("DEVICE_STORE_TIME_PRECISION");
            }
            if (throughSequence < proof.fromSequence() || !coverageStart.isBefore(watermark)
                    || keyVersion < 1 || keyVersion > 65_535) {
                throw new IllegalArgumentException("DEVICE_STORE_COVERAGE_SEAL");
            }
        }
    }

    record Provenance(String receiptId, String rawCommitment, String enrichedCommitment, String observationCommitment,
                      String populationCommitment, String originPolicyVersion, String deviceIssuer,
                      String deviceKeyVersion, String devicePolicyVersion, String deviceKeyCommitment,
                      String authorizationCommitment, Instant proofTime, long sequenceOrigin, String commitment) {
        Provenance(String receiptId, String rawCommitment, String enrichedCommitment, String observationCommitment,
                   String populationCommitment, String originPolicyVersion, String deviceIssuer,
                   String deviceKeyVersion, String devicePolicyVersion, String deviceKeyCommitment,
                   String authorizationCommitment, Instant proofTime, long sequenceOrigin) {
            this(receiptId, rawCommitment, enrichedCommitment, observationCommitment, populationCommitment,
                    originPolicyVersion, deviceIssuer, deviceKeyVersion, devicePolicyVersion, deviceKeyCommitment,
                    authorizationCommitment, proofTime, sequenceOrigin, null);
        }
        public Provenance {
            token(receiptId); token(rawCommitment); token(enrichedCommitment); token(observationCommitment); token(populationCommitment); token(deviceKeyCommitment);
            DeviceAssertions.reference(originPolicyVersion); DeviceAssertions.reference(deviceIssuer);
            DeviceAssertions.reference(deviceKeyVersion); DeviceAssertions.reference(devicePolicyVersion);
            token(authorizationCommitment);
            if (sequenceOrigin < 1 || proofTime == null || proofTime.getNano() % 1_000_000 != 0) { throw new IllegalArgumentException("DEVICE_STORE_SEQUENCE_ORIGIN"); }
            commitment = DeviceEpoch.digest("device-provenance/v1", receiptId, rawCommitment, enrichedCommitment, observationCommitment,
                    populationCommitment, originPolicyVersion, deviceIssuer, deviceKeyVersion, devicePolicyVersion, deviceKeyCommitment,
                    authorizationCommitment, proofTime.toString(), Long.toString(sequenceOrigin));
        }
        org.bson.Document document() {
            return new org.bson.Document("receiptId", receiptId).append("rawCommitment", rawCommitment).append("enrichedCommitment", enrichedCommitment)
                    .append("observationCommitment", observationCommitment).append("populationCommitment", populationCommitment)
                    .append("originPolicyVersion", originPolicyVersion).append("deviceIssuer", deviceIssuer).append("deviceKeyVersion", deviceKeyVersion)
                    .append("devicePolicyVersion", devicePolicyVersion).append("deviceKeyCommitment", deviceKeyCommitment)
                    .append("authorizationCommitment", authorizationCommitment).append("proofTime", java.util.Date.from(proofTime)).append("sequenceOrigin", sequenceOrigin);
        }
        static Provenance from(org.bson.Document row) {
            org.bson.Document p = row.get("provenance", org.bson.Document.class);
            Provenance result = new Provenance(p.getString("receiptId"), p.getString("rawCommitment"), p.getString("enrichedCommitment"),
                    p.getString("observationCommitment"), p.getString("populationCommitment"), p.getString("originPolicyVersion"), p.getString("deviceIssuer"),
                    p.getString("deviceKeyVersion"), p.getString("devicePolicyVersion"), p.getString("deviceKeyCommitment"), p.getString("authorizationCommitment"),
                    p.getDate("proofTime").toInstant(), p.getLong("sequenceOrigin"));
            if (!result.commitment().equals(row.getString("provenanceCommitment"))) { throw new IllegalStateException("DEVICE_STORE_PROVENANCE_CONFLICT"); }
            return result;
        }
        @Override public String toString() { return "DeviceProvenance[redacted]"; }
    }

    record CoverageProof(long sequenceOrigin, long fromSequence, long generation, String previousCommitment,
                         String coverageCommitment, String assertionCommitment, String populationCommitment, CoverageAuthority authority) {
        public CoverageProof {
            Objects.requireNonNull(authority);
            token(previousCommitment); token(coverageCommitment); token(assertionCommitment); token(populationCommitment);
            if (sequenceOrigin < 1 || fromSequence < sequenceOrigin || generation < 1
                    || generation == 1 && (fromSequence != sequenceOrigin || !previousCommitment.equals("0".repeat(64)))
                    || generation > 1 && previousCommitment.equals("0".repeat(64))) {
                throw new IllegalArgumentException("DEVICE_STORE_COVERAGE_PROOF");
            }
        }
        @Override public String toString() { return "DeviceCoverageProof[redacted]"; }
    }

    record CoverageAuthority(String issuer, String keyVersion, String policyVersion, String keyCommitment,
                             String authorizationCommitment, String payloadCommitment, String coverageId, Instant proofTime) {
        public CoverageAuthority {
            DeviceAssertions.reference(issuer); DeviceAssertions.reference(keyVersion); DeviceAssertions.reference(policyVersion);
            token(keyCommitment); token(authorizationCommitment); token(payloadCommitment); DeviceAssertions.reference(coverageId);
            if (proofTime == null || proofTime.getNano() % 1_000_000 != 0) { throw new IllegalArgumentException("DEVICE_COVERAGE_TIME_INVALID"); }
        }
        org.bson.Document document() {
            return new org.bson.Document("issuer", issuer).append("keyVersion", keyVersion).append("policyVersion", policyVersion).append("keyCommitment", keyCommitment)
                    .append("authorizationCommitment", authorizationCommitment).append("payloadCommitment", payloadCommitment).append("coverageId", coverageId)
                    .append("proofTime", java.util.Date.from(proofTime));
        }
        static CoverageAuthority from(org.bson.Document row) {
            var a = row.get("coverageAuthority", org.bson.Document.class);
            return new CoverageAuthority(a.getString("issuer"), a.getString("keyVersion"), a.getString("policyVersion"), a.getString("keyCommitment"),
                    a.getString("authorizationCommitment"), a.getString("payloadCommitment"), a.getString("coverageId"), a.getDate("proofTime").toInstant());
        }
        @Override public String toString() { return "DeviceCoverageAuthority[redacted]"; }
    }

    @FunctionalInterface
    interface HistoryAuthority {
        boolean current(Provenance provenance, int pseudonymKeyVersion);
        default boolean currentHistory(List<Provenance> provenance, int pseudonymKeyVersion) {
            return provenance.stream().allMatch(item -> current(item, pseudonymKeyVersion));
        }
        default boolean coverageCurrent(CoverageAuthority proof, String institution, String source, String populationCommitment,
                                        int pseudonymKeyVersion, Instant coverageStart) { return false; }
    }

    private static String token(String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("DEVICE_STORE_TOKEN");
        }
        return value;
    }

    private static String code(String value) {
        if (value == null || !SOURCE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("DEVICE_STORE_SOURCE");
        }
        return value;
    }
}
