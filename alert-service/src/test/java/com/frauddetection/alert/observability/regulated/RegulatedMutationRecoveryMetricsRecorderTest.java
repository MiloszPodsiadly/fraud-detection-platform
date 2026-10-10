package com.frauddetection.alert.observability.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.observability.audit.AuditIntegrityMetricsRecorder;
import com.frauddetection.alert.observability.evidence.EvidenceSnapshotMetricsRecorder;
import com.frauddetection.alert.observability.outbox.OutboxMetricsRecorder;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationRecoveryMetricsRecorderTest {

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
        RegulatedMutationRecoveryMetricsRecorder recorder =
                new RegulatedMutationRecoveryMetricsRecorder(recorderRegistry);

        facade.recordRegulatedMutationRecoveryBacklog(3, 4L, 5, 6);
        facade.recordRegulatedMutationRecoveryOutcome("STILL_PENDING");
        recorder.recordBacklog(3, 4L, 5, 6);
        recorder.recordOutcome("STILL_PENDING");

        assertThat(meterIdentities(facadeRegistry)).containsExactlyElementsOf(meterIdentities(recorderRegistry));
        for (String gauge : List.of(
                "regulated_mutation_recovery_required_count",
                "regulated_mutation_recovery_oldest_age_seconds",
                "regulated_mutation_recovery_failed_terminal_count",
                "regulated_mutation_recovery_repeated_failures_count"
        )) {
            assertThat(facadeRegistry.get(gauge).gauge().value())
                    .isEqualTo(recorderRegistry.get(gauge).gauge().value());
        }
        assertThat(facadeRegistry.get("regulated_mutation_recovery_outcome_total")
                .tag("outcome", "STILL_PENDING")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void sharedSpringCompositionRegistersOneGaugeAndOneCounter() {
        contextRunner.run(context -> {
            AlertServiceMetrics facade = context.getBean(AlertServiceMetrics.class);
            facade.recordRegulatedMutationRecoveryBacklog(2, 3L);
            facade.recordRegulatedMutationRecoveryOutcome("RECOVERED");

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find("regulated_mutation_recovery_required_count").meters()).hasSize(1);
            assertThat(registry.find("regulated_mutation_recovery_outcome_total").meters()).hasSize(1);
            assertThat(registry.get("regulated_mutation_recovery_outcome_total")
                    .tag("outcome", "RECOVERED")
                    .counter()
                    .count()).isEqualTo(1.0);
        });
    }

    private List<String> meterIdentities(MeterRegistry registry) {
        return registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("regulated_mutation_recovery_"))
                .map(id -> id.getName() + "|" + id.getType() + "|" + id.getTags())
                .sorted()
                .toList();
    }
}
