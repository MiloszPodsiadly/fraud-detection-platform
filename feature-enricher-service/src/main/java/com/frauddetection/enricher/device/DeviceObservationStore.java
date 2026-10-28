package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Internal persistence seam only. This type does not authenticate observations or coverage seals. */
interface DeviceObservationStore {
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
                       String payloadCommitment, DeviceRiskEvidence.SourceQuality sourceQuality) {
        public Observation {
            institutionToken = token(institutionToken);
            sourceCode = code(sourceCode);
            sourceEventKey = token(sourceEventKey);
            transactionKey = token(transactionKey);
            customerToken = token(customerToken);
            deviceToken = token(deviceToken);
            payloadCommitment = token(payloadCommitment);
            if (keyVersion < 1 || keyVersion > 65_535 || sourceSequence < 1
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
                        Instant coverageStart, Instant watermark, int keyVersion) {
        public CoverageSeal {
            institutionToken = token(institutionToken);
            sourceCode = code(sourceCode);
            Objects.requireNonNull(coverageStart, "coverageStart");
            Objects.requireNonNull(watermark, "watermark");
            if (coverageStart.getNano() % 1_000_000 != 0 || watermark.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("DEVICE_STORE_TIME_PRECISION");
            }
            if (throughSequence < 1 || !coverageStart.isBefore(watermark)
                    || keyVersion < 1 || keyVersion > 65_535) {
                throw new IllegalArgumentException("DEVICE_STORE_COVERAGE_SEAL");
            }
        }
    }

    private static String token(String value) {
        if (value == null || !Pattern.matches("[0-9a-f]{64}", value)) {
            throw new IllegalArgumentException("DEVICE_STORE_TOKEN");
        }
        return value;
    }

    private static String code(String value) {
        if (value == null || !Pattern.matches("[A-Z][A-Z0-9_]{0,63}", value)) {
            throw new IllegalArgumentException("DEVICE_STORE_SOURCE");
        }
        return value;
    }
}
