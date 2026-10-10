package com.frauddetection.alert.observability.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.observability.evidence.EvidenceSnapshotMetricsRecorder;
import com.frauddetection.alert.observability.outbox.OutboxMetricsRecorder;
import com.frauddetection.alert.observability.regulated.RegulatedMutationRecoveryMetricsRecorder;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditIntegrityMetricsRecorderTest {

    private static final Set<String> METRIC_NAMES = Set.of(
            "fraud_audit_chain_head_hash",
            "fraud_audit_last_anchor_hash",
            "fraud_audit_integrity_status",
            "fraud_platform_audit_integrity_checks_total",
            "fraud_platform_audit_integrity_check_total",
            "fraud_audit_integrity_check_total",
            "fraud_platform_audit_external_integrity_checks_total",
            "fraud_platform_audit_integrity_violations_total",
            "fraud_audit_integrity_violation_total"
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(EvidenceSnapshotMetricsRecorder.class)
            .withBean(AuditIntegrityMetricsRecorder.class)
            .withBean(OutboxMetricsRecorder.class)
            .withBean(RegulatedMutationRecoveryMetricsRecorder.class)
            .withBean(AlertServiceMetrics.class);

    @Test
    void compatibilityFacadeAndOwnedRecorderExposeIdenticalMetersAndValues() {
        SimpleMeterRegistry facadeRegistry = new SimpleMeterRegistry();
        SimpleMeterRegistry recorderRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics facade = new AlertServiceMetrics(facadeRegistry);
        AuditIntegrityMetricsRecorder recorder = new AuditIntegrityMetricsRecorder(recorderRegistry);

        recordAll(facade);
        recordAll(recorder);

        assertThat(meterIdentities(facadeRegistry)).containsExactlyElementsOf(meterIdentities(recorderRegistry));
        for (String gauge : List.of("fraud_audit_chain_head_hash", "fraud_audit_last_anchor_hash")) {
            assertThat(facadeRegistry.get(gauge).gauge().value())
                    .isEqualTo(recorderRegistry.get(gauge).gauge().value());
        }
        assertThat(facadeRegistry.get("fraud_audit_integrity_status")
                .tag("status", "VALID")
                .gauge()
                .value()).isEqualTo(1.0);
    }

    @Test
    void sharedSpringCompositionRegistersMetersOnceAndRecordsOnce() {
        contextRunner.run(context -> {
            AlertServiceMetrics facade = context.getBean(AlertServiceMetrics.class);
            facade.recordAuditIntegrityCheck("VALID");

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find("fraud_platform_audit_integrity_checks_total").meters()).hasSize(1);
            assertThat(registry.get("fraud_platform_audit_integrity_checks_total")
                    .tag("status", "VALID")
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    private void recordAll(AlertServiceMetrics facade) {
        facade.recordAuditIntegrityCheck("VALID");
        facade.recordForensicAuditIntegrityCheck("PARTIAL");
        facade.recordExternalIntegrityCheck("INVALID");
        facade.recordAuditIntegrityViolation("CHAIN_FORK_DETECTED");
        facade.recordForensicAuditIntegrityViolation("SIGNATURE_INVALID");
        facade.recordAuditIntegritySnapshot("VALID", "0123456789abcdef", "abcdef0123456789");
    }

    private void recordAll(AuditIntegrityMetricsRecorder recorder) {
        recorder.recordPlatformCheck("VALID");
        recorder.recordForensicCheck("PARTIAL");
        recorder.recordExternalCheck("INVALID");
        recorder.recordPlatformViolation("CHAIN_FORK_DETECTED");
        recorder.recordForensicViolation("SIGNATURE_INVALID");
        recorder.recordSnapshot("VALID", "0123456789abcdef", "abcdef0123456789");
    }

    private List<String> meterIdentities(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> METRIC_NAMES.contains(id.getName()))
                .map(id -> id.getName() + "|" + id.getType() + "|" + id.getTags())
                .sorted()
                .toList();
    }
}
