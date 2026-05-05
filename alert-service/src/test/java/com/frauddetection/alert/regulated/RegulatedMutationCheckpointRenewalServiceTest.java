package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegulatedMutationCheckpointRenewalServiceTest {

    private final RegulatedMutationLeaseRenewalService leaseRenewalService = mock(RegulatedMutationLeaseRenewalService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
    private final RegulatedMutationCheckpointRenewalService service = new RegulatedMutationCheckpointRenewalService(
            new RegulatedMutationSafeCheckpointPolicy(),
            leaseRenewalService,
            metrics,
            Duration.ofSeconds(10),
            Clock.fixed(Instant.parse("2026-05-05T08:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void allowedCheckpointDelegatesToBoundedLeaseRenewalAndUpdatesLocalDocumentLease() {
        RegulatedMutationClaimToken token = token(RegulatedMutationState.AUDIT_ATTEMPTED);
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.AUDIT_ATTEMPTED);
        Instant renewedUntil = Instant.parse("2026-05-05T08:00:20Z");
        when(leaseRenewalService.renew(token, Duration.ofSeconds(10))).thenReturn(RegulatedMutationLeaseRenewalDecision.renew(
                renewedUntil,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                false,
                false
        ));

        RegulatedMutationCheckpointRenewalDecision decision = service.beforeLegacyBusinessCommit(token, document);

        assertThat(decision.type()).isEqualTo(RegulatedMutationCheckpointRenewalDecisionType.RENEWED);
        assertThat(decision.checkpoint()).isEqualTo(RegulatedMutationRenewalCheckpoint.BEFORE_LEGACY_BUSINESS_COMMIT);
        assertThat(document.getLeaseExpiresAt()).isEqualTo(renewedUntil);
        verify(leaseRenewalService).renew(token, Duration.ofSeconds(10));
        assertThat(meterRegistry.get("regulated_mutation_checkpoint_renewal_total")
                .tag("checkpoint", "BEFORE_LEGACY_BUSINESS_COMMIT")
                .tag("outcome", "RENEWED")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.find("regulated_mutation_checkpoint_no_progress_total").counter()).isNull();
    }

    @Test
    void unsupportedCheckpointFailsClosedBeforeCallingRenewalPrimitive() {
        RegulatedMutationClaimToken token = token(RegulatedMutationState.REQUESTED);
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.REQUESTED);

        assertThatThrownBy(() -> service.beforeLegacyBusinessCommit(token, document))
                .isInstanceOf(RegulatedMutationCheckpointRenewalException.class)
                .hasMessageContaining("NON_RENEWABLE_STATE");

        verifyNoInteractions(leaseRenewalService);
        assertThat(meterRegistry.get("regulated_mutation_checkpoint_renewal_blocked_total")
                .tag("checkpoint", "BEFORE_LEGACY_BUSINESS_COMMIT")
                .tag("reason", "NON_RENEWABLE_STATE")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("regulated_mutation_checkpoint_no_progress_total")
                .tag("checkpoint", "BEFORE_LEGACY_BUSINESS_COMMIT")
                .tag("reason", "NON_RENEWABLE_STATE")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void budgetExceededFromRenewalPrimitivePropagatesAndRecordsFailedCheckpoint() {
        RegulatedMutationClaimToken token = token(RegulatedMutationState.AUDIT_ATTEMPTED);
        RegulatedMutationCommandDocument document = document(RegulatedMutationState.AUDIT_ATTEMPTED);
        when(leaseRenewalService.renew(token, Duration.ofSeconds(10)))
                .thenThrow(new RegulatedMutationLeaseRenewalBudgetExceededException("command-1"));

        assertThatThrownBy(() -> service.beforeLegacyBusinessCommit(token, document))
                .isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        assertThat(meterRegistry.get("regulated_mutation_checkpoint_renewal_total")
                .tag("checkpoint", "BEFORE_LEGACY_BUSINESS_COMMIT")
                .tag("outcome", "FAILED")
                .tag("reason", "BUDGET_EXCEEDED")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("regulated_mutation_checkpoint_no_progress_total")
                .tag("checkpoint", "BEFORE_LEGACY_BUSINESS_COMMIT")
                .tag("reason", "BUDGET_EXCEEDED")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private RegulatedMutationClaimToken token(RegulatedMutationState state) {
        return new RegulatedMutationClaimToken(
                "command-1",
                "owner-1",
                Instant.parse("2026-05-05T08:00:10Z"),
                Instant.parse("2026-05-05T08:00:00Z"),
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                state,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }

    private RegulatedMutationCommandDocument document(RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setMutationModelVersion(RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        document.setState(state);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseOwner("owner-1");
        document.setLeaseExpiresAt(Instant.parse("2026-05-05T08:00:10Z"));
        return document;
    }
}
