package com.frauddetection.alert.regulated;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationLeaseRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-05T10:00:00Z");

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final RegulatedMutationLeaseRenewalPolicy policy =
            new RegulatedMutationLeaseRenewalPolicy(Duration.ofSeconds(30), Duration.ofMinutes(2), 3);
    private final RegulatedMutationLeaseRenewalFailureHandler failureHandler =
            new RegulatedMutationLeaseRenewalFailureHandler(
                    mongoTemplate,
                    policy,
                    new RegulatedMutationPublicStatusMapper()
            );
    private final RegulatedMutationLeaseRenewalService service = new RegulatedMutationLeaseRenewalService(
            mongoTemplate,
            policy,
            failureHandler,
            new AlertServiceMetrics(new SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void renewalUsesConditionalOwnerFencedMongoUpdateOnlyForLeaseMetadata() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(document());
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.renew(token(), Duration.ofSeconds(40));

        String query = capturedQuery().getQueryObject().toString();
        assertThat(query)
                .contains("_id=command-1")
                .contains("lease_owner=owner-1")
                .contains("lease_expires_at")
                .contains("execution_status=PROCESSING")
                .contains("state=REQUESTED")
                .contains("mutation_model_version");

        Document set = updateDocument("$set");
        Document inc = updateDocument("$inc");
        assertThat(set).containsKeys(
                "lease_expires_at",
                "last_heartbeat_at",
                "last_lease_renewed_at",
                "lease_budget_started_at",
                "updated_at"
        );
        assertThat(inc.get("lease_renewal_count")).isEqualTo(1);
        assertThat(set).doesNotContainKeys(
                "idempotency_key",
                "request_hash",
                "actor_id",
                "resource_id",
                "action",
                "resource_type",
                "response_snapshot",
                "outbox_event_id",
                "local_commit_marker",
                "success_audit_id",
                "success_audit_recorded",
                "public_status",
                "state",
                "execution_status"
        );
    }

    @Test
    void invalidExtensionThrowsRenewalExceptionWithoutBudgetRecoveryUpdate() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(document());

        assertThatThrownBy(() -> service.renew(token(), Duration.ZERO))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.INVALID_EXTENSION);

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void commandNotFoundThrowsRenewalExceptionWithoutBudgetRecoveryUpdate() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(null);

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.COMMAND_NOT_FOUND);

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void terminalRaceAfterRenewalUpdateRejectsWithoutRecoveryWrite() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(
                        document(),
                        document(
                                RegulatedMutationState.COMMITTED,
                                RegulatedMutationExecutionStatus.PROCESSING,
                                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
                        )
                );
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);

        verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void recoveryRaceAfterRenewalUpdateRejectsWithoutRecoveryWrite() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(
                        document(),
                        document(
                                RegulatedMutationState.FAILED,
                                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
                        )
                );
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.RECOVERY_STATE);

        verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void expiredRaceAfterRenewalUpdateThrowsStaleLease() {
        RegulatedMutationCommandDocument expired = document();
        expired.setLeaseExpiresAt(NOW.minusMillis(1));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(document(), expired);
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPIRED_LEASE);

        verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void modelVersionRaceAfterRenewalUpdateRejectsWithoutRecoveryWrite() {
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(
                        document(),
                        document(
                                RegulatedMutationState.EVIDENCE_PREPARING,
                                RegulatedMutationExecutionStatus.PROCESSING,
                                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
                        )
                );
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.MODEL_VERSION_MISMATCH);

        verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    @Test
    void sameOwnerRaceThatAlreadyAdvancedLastRenewalSlotDoesNotMarkRecoveryRequired() {
        RegulatedMutationCommandDocument before = document();
        before.setLeaseRenewalCount(2);
        RegulatedMutationCommandDocument afterRace = document();
        afterRace.setLeaseRenewalCount(3);
        afterRace.setLeaseExpiresAt(NOW.plusSeconds(30));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class))
                .thenReturn(before, afterRace);
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.renew(token(), Duration.ofSeconds(40)))
                .isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        verify(mongoTemplate, times(1)).updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class));
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(
                captor.capture(),
                any(Update.class),
                eq(RegulatedMutationCommandDocument.class)
        );
        return captor.getValue();
    }

    private Document updateDocument(String key) {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class),
                captor.capture(),
                eq(RegulatedMutationCommandDocument.class)
        );
        return (Document) captor.getValue().getUpdateObject().get(key);
    }

    private RegulatedMutationCommandDocument document() {
        return document(
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        );
    }

    private RegulatedMutationCommandDocument document(
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus status,
            RegulatedMutationModelVersion modelVersion
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setLeaseOwner("owner-1");
        document.setLeaseExpiresAt(NOW.plusSeconds(5));
        document.setLeaseBudgetStartedAt(NOW);
        document.setMutationModelVersion(modelVersion);
        document.setState(state);
        document.setExecutionStatus(status);
        return document;
    }

    private RegulatedMutationClaimToken token() {
        return new RegulatedMutationClaimToken(
                "command-1",
                "owner-1",
                NOW.plusSeconds(5),
                NOW,
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }
}
