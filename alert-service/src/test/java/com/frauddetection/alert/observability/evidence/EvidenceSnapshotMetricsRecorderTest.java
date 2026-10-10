package com.frauddetection.alert.observability.evidence;

import com.frauddetection.alert.evidence.EvidenceProjectionState;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.observability.audit.AuditIntegrityMetricsRecorder;
import com.frauddetection.alert.observability.outbox.OutboxMetricsRecorder;
import com.frauddetection.alert.observability.regulated.RegulatedMutationRecoveryMetricsRecorder;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSnapshotMetricsRecorderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(EvidenceSnapshotMetricsRecorder.class)
            .withBean(AuditIntegrityMetricsRecorder.class)
            .withBean(OutboxMetricsRecorder.class)
            .withBean(RegulatedMutationRecoveryMetricsRecorder.class)
            .withBean(AlertServiceMetrics.class);

    @Test
    void compatibilityFacadeAndFeatureRecorderExposeIdenticalMetricFamilies() {
        SimpleMeterRegistry facadeRegistry = new SimpleMeterRegistry();
        SimpleMeterRegistry recorderRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics facade = new AlertServiceMetrics(facadeRegistry);
        EvidenceSnapshotMetricsRecorder recorder = new EvidenceSnapshotMetricsRecorder(recorderRegistry);

        facade.recordEvidenceSnapshotProjectionSuccess();
        facade.recordEvidenceSnapshotProjectionDiagnostic(EvidenceProjectionState.PARTIAL_TRUNCATED);
        facade.recordEvidenceSnapshotProjectionTruncated();
        facade.recordEvidenceSnapshotProjectionError();
        recorder.recordProjectionSuccess();
        recorder.recordProjectionDiagnostic(EvidenceProjectionState.PARTIAL_TRUNCATED);
        recorder.recordProjectionTruncated();
        recorder.recordProjectionError();

        assertThat(meterIdentities(facadeRegistry))
                .containsExactlyElementsOf(meterIdentities(recorderRegistry));
        assertCounter(facadeRegistry, "fraud.alert.evidence_snapshot.projection.success", "outcome", "success");
        assertCounter(
                facadeRegistry,
                "fraud.alert.evidence_snapshot.projection.diagnostic",
                "state", "PARTIAL_TRUNCATED"
        );
        assertCounter(facadeRegistry, "fraud.alert.evidence_snapshot.projection.truncated", "outcome", "truncated");
        assertCounter(facadeRegistry, "fraud.alert.evidence_snapshot.projection.error", "outcome", "error");
    }

    @Test
    void sharedSpringCompositionRegistersOneMeterAndRecordsOncePerCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EvidenceSnapshotMetricsRecorder recorder = new EvidenceSnapshotMetricsRecorder(registry);
        AlertServiceMetrics facade = new AlertServiceMetrics(registry, recorder);

        facade.recordEvidenceSnapshotProjectionSuccess();

        assertThat(registry.find("fraud.alert.evidence_snapshot.projection.success").meters()).hasSize(1);
        assertThat(registry.get("fraud.alert.evidence_snapshot.projection.success")
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void springWiresOneFeatureRecorderIntoCompatibilityFacade() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EvidenceSnapshotMetricsRecorder.class);
            assertThat(context).hasSingleBean(AlertServiceMetrics.class);

            context.getBean(AlertServiceMetrics.class).recordEvidenceSnapshotProjectionError();

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find("fraud.alert.evidence_snapshot.projection.error").meters()).hasSize(1);
            assertThat(registry.get("fraud.alert.evidence_snapshot.projection.error")
                    .tag("outcome", "error")
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    @Test
    void diagnosticStateLabelsRemainBoundedToEnumValuesAndUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EvidenceSnapshotMetricsRecorder recorder = new EvidenceSnapshotMetricsRecorder(registry);

        Arrays.stream(EvidenceProjectionState.values()).forEach(recorder::recordProjectionDiagnostic);
        recorder.recordProjectionDiagnostic(null);

        assertThat(registry.find("fraud.alert.evidence_snapshot.projection.diagnostic").counters())
                .hasSize(EvidenceProjectionState.values().length + 1)
                .extracting(counter -> counter.getId().getTag("state"))
                .containsExactlyInAnyOrder(
                        "PROJECTED",
                        "PARTIAL_MISSING_SOURCE_EVENT_ID",
                        "PARTIAL_MISSING_TRANSACTION_ID",
                        "PARTIAL_MISSING_CORRELATION_ID",
                        "PARTIAL_MISSING_REQUIRED_LINEAGE",
                        "PARTIAL_EMPTY_SCORING_EVIDENCE",
                        "PARTIAL_TRUNCATED",
                        "UNAVAILABLE_UNSUPPORTED_EVIDENCE",
                        "ERROR_PROJECTION_FAILED",
                        "ERROR_PROJECTED",
                        "UNKNOWN"
                );
    }

    private List<String> meterIdentities(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("fraud.alert.evidence_snapshot."))
                .map(id -> id.getName() + "|" + id.getType() + "|" + id.getTags())
                .sorted()
                .toList();
    }

    private void assertCounter(
            MeterRegistry registry,
            String name,
            String tagName,
            String tagValue
    ) {
        assertThat(registry.get(name).tag(tagName, tagValue).counter().count()).isEqualTo(1.0);
    }
}
