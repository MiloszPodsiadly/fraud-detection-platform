package com.frauddetection.alert.observability.evidence;

import com.frauddetection.alert.evidence.EvidenceProjectionState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class EvidenceSnapshotMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public EvidenceSnapshotMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    public void recordProjectionSuccess() {
        counter("fraud.alert.evidence_snapshot.projection.success", "outcome", "success").increment();
    }

    public void recordProjectionDiagnostic(EvidenceProjectionState state) {
        counter(
                "fraud.alert.evidence_snapshot.projection.diagnostic",
                "outcome", "diagnostic",
                "state", normalizeProjectionState(state)
        ).increment();
    }

    public void recordProjectionTruncated() {
        counter("fraud.alert.evidence_snapshot.projection.truncated", "outcome", "truncated").increment();
    }

    public void recordProjectionError() {
        counter("fraud.alert.evidence_snapshot.projection.error", "outcome", "error").increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    private String normalizeProjectionState(EvidenceProjectionState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state) {
            case PROJECTED,
                 PARTIAL_MISSING_SOURCE_EVENT_ID,
                 PARTIAL_MISSING_TRANSACTION_ID,
                 PARTIAL_MISSING_CORRELATION_ID,
                 PARTIAL_MISSING_REQUIRED_LINEAGE,
                 PARTIAL_EMPTY_SCORING_EVIDENCE,
                 PARTIAL_TRUNCATED,
                 UNAVAILABLE_UNSUPPORTED_EVIDENCE,
                 ERROR_PROJECTED,
                 ERROR_PROJECTION_FAILED -> state.name();
        };
    }
}
