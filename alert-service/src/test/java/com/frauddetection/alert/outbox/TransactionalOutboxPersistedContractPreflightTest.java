package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.service.FraudDecisionOutboxPublisher;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalOutboxPersistedContractPreflightTest {

    private final TransactionalOutboxPersistedContractPreflight preflight =
            new TransactionalOutboxPersistedContractPreflight(null);

    @Test
    void rejectsOldPendingWithoutProjectionRevision() {
        Document source = canonicalOutbox("event-pending", "alert-pending", "PENDING", 0L);
        source.remove("projection_revision");

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedUnfinishedCount()).isOne();
        assertThat(violations(report)).contains("PROJECTION_REVISION_MISSING_OR_INVALID");
    }

    @Test
    void rejectsOldPublishedWithoutPublicationProvenance() {
        Document source = canonicalOutbox("event-published", "alert-published", "PUBLISHED", 1L);
        source.remove("publication_confirmation_provenance");

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedTerminalCount()).isOne();
        assertThat(violations(report)).contains("PUBLISHED_PROVENANCE_MISSING_OR_INVALID");
    }

    @Test
    void rejectsActiveClaimWithoutPerClaimGenerationToken() {
        Document source = canonicalOutbox("event-old-claim", "alert-old-claim", "PROCESSING", 1L)
                .append("lease_owner", "coordinator-1")
                .append("lease_expires_at", Instant.parse("2026-10-01T10:05:00Z"));

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedUnfinishedCount()).isOne();
        assertThat(violations(report)).contains("ACTIVE_CLAIM_TOKEN_MISSING_OR_INVALID");
    }

    @Test
    void acceptsActiveClaimWithCompletePerClaimGenerationFence() {
        Document source = canonicalOutbox("event-current-claim", "alert-current-claim", "PROCESSING", 1L)
                .append("lease_owner", "coordinator-1")
                .append("lease_claim_token", "claim-generation-1")
                .append("lease_expires_at", Instant.parse("2026-10-01T10:05:00Z"));

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.blocksStartup()).isFalse();
    }

    @Test
    void rejectsOldAlertProjectionWithoutEventIdentityAndRevision() {
        Document source = canonicalOutbox("event-alert-shape", "alert-old", "PENDING", 0L);
        Document alert = canonicalAlert("alert-old", "event-alert-shape", "PENDING", 0L);
        alert.remove("decisionOutboxEventId");
        alert.remove("decisionOutboxProjectionRevision");

        TransactionalOutboxPersistedContractPreflight.Report report =
                preflight.inspectRawDocuments(List.of(source), List.of(alert), 10);

        assertThat(report.unsupportedUnfinishedCount()).isOne();
        assertThat(report.unsupportedAlertProjectionCount()).isOne();
        assertThat(violations(report)).contains(
                "ALERT_EVENT_ID_MISSING_OR_INVALID",
                "ALERT_PROJECTION_REVISION_MISSING_OR_INVALID",
                "AUTHORITATIVE_OUTBOX_MISSING"
        );
    }

    @Test
    void rejectsRetiredResolutionReasonInTerminalRecord() {
        Document source = canonicalOutbox("event-retired", "alert-retired", "FAILED_TERMINAL", 2L)
                .append("resolution_reason", "ambiguous historical reason");

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedTerminalCount()).isOne();
        assertThat(violations(report)).contains("RETIRED_RESOLUTION_REASON_PRESENT");
    }

    @Test
    void rejectsIncompletePendingDualControlWithoutManufacturingIntent() {
        Document source = canonicalOutbox(
                "event-incomplete-pending",
                "alert-incomplete-pending",
                "PUBLISH_CONFIRMATION_UNKNOWN",
                3L
        ).append("resolution_pending", true)
                .append("resolution_control_mode", "DUAL_CONTROL_REQUESTED")
                .append("resolution_requested_by", "requester");

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedUnfinishedCount()).isOne();
        assertThat(violations(report)).contains("PENDING_INTENT_INCOMPLETE");
    }

    @Test
    void rejectsUnsupportedTerminalApprovalEvidence() {
        Document source = canonicalOutbox("event-terminal", "alert-terminal", "PUBLISHED", 4L)
                .append("publication_confirmation_provenance", "MANUAL_DUAL_CONTROL_ATTESTED")
                .append("resolution_pending", false)
                .append("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .append("resolution_request_id", "request-1")
                .append("resolution_proposed_outcome", "PUBLISHED");

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source);

        assertThat(report.unsupportedTerminalCount()).isOne();
        assertThat(violations(report)).contains(
                "PENDING_INTENT_INCOMPLETE",
                "DUAL_CONTROL_APPROVAL_INCOMPLETE"
        );
    }

    @Test
    void acceptsCanonicalDocumentsAndPremigratedPendingDualControl() {
        Document ordinary = canonicalOutbox("event-current", "alert-current", "PENDING", 0L);
        Document pendingDual = canonicalOutbox(
                "event-dual",
                "alert-dual",
                "PUBLISH_CONFIRMATION_UNKNOWN",
                2L
        ).append("resolution_pending", true)
                .append("resolution_control_mode", "DUAL_CONTROL_REQUESTED")
                .append("resolution_request_id", "request-1")
                .append("resolution_proposed_outcome", "PUBLISHED")
                .append("resolution_requested_by", "requester")
                .append("resolution_requested_at", Instant.parse("2026-10-01T10:00:00Z"))
                .append("resolution_request_reason", "verified broker evidence")
                .append("resolution_evidence_type", "BROKER_OFFSET")
                .append("resolution_evidence_reference", "partition=1,offset=42")
                .append("resolution_evidence_verified_at", Instant.parse("2026-10-01T10:00:01Z"))
                .append("resolution_evidence_verified_by", "request-verifier")
                .append("resolution_evidence_fingerprint", "request-fingerprint");

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspectRawDocuments(
                List.of(ordinary, pendingDual),
                List.of(
                        canonicalAlert("alert-current", "event-current", "PENDING", 0L),
                        canonicalAlert(
                                "alert-dual",
                                "event-dual",
                                "PUBLISH_CONFIRMATION_UNKNOWN",
                                2L
                        )
                ),
                10
        );

        assertThat(report.blocksStartup()).isFalse();
        assertThat(report.samples()).isEmpty();
    }

    @Test
    void rejectsInconsistentProjectionIdentityAndRevisionWithoutExposingRawIds() {
        Document source = canonicalOutbox("sensitive-event-id", "sensitive-alert-id", "PENDING", 1L);
        Document alert = canonicalAlert("sensitive-alert-id", "different-event-id", "PENDING", 2L);

        TransactionalOutboxPersistedContractPreflight.Report report =
                preflight.inspectRawDocuments(List.of(source), List.of(alert), 10);

        assertThat(report.blocksStartup()).isTrue();
        assertThat(violations(report)).contains(
                "ALERT_EVENT_ID_DOES_NOT_MATCH_SOURCE",
                "ALERT_PROJECTION_NEWER_THAN_SOURCE"
        );
        assertThat(report.samples().toString())
                .doesNotContain("sensitive-event-id")
                .doesNotContain("sensitive-alert-id");
    }

    @Test
    void startupFailureKeepsPublisherAndRecoveryMutationsBehindReadinessBarrier() {
        TransactionalOutboxPersistedContractPreflight mockedPreflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(mockedPreflight.inspect(25)).thenReturn(blockingReport());
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(mockedPreflight, readiness);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported persisted records");

        OutboxPublisherCoordinator coordinator = mock(OutboxPublisherCoordinator.class);
        FraudDecisionOutboxPublisher publisher = new FraudDecisionOutboxPublisher(coordinator, readiness);
        OutboxRecoveryService recoveryService = mock(OutboxRecoveryService.class);
        OutboxRecoveryController controller = new OutboxRecoveryController(
                recoveryService,
                mock(SensitiveReadAuditService.class),
                readiness
        );

        assertThatThrownBy(() -> publisher.publishPending(100)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(controller::recoverNow).isInstanceOf(IllegalStateException.class);
        verify(coordinator, never()).publishPending(100);
        verify(recoveryService, never()).recoverNow();
    }

    @Test
    void successfulPreflightOpensRuntimeBarrier() throws Exception {
        TransactionalOutboxPersistedContractPreflight mockedPreflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(mockedPreflight.inspect(25)).thenReturn(
                new TransactionalOutboxPersistedContractPreflight.Report(0, 0, 0, List.of()));
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(mockedPreflight, readiness);

        guard.run(mock(ApplicationArguments.class));

        assertThat(readiness.isReady()).isTrue();
    }

    private TransactionalOutboxPersistedContractPreflight.Report inspect(Document source) {
        String alertId = source.getString("resource_id");
        String eventId = source.getString("_id");
        String status = source.getString("status");
        long revision = source.get("projection_revision") instanceof Number number ? number.longValue() : 0L;
        return preflight.inspectRawDocuments(
                List.of(source),
                List.of(canonicalAlert(alertId, eventId, projectionStatus(status), revision)),
                10
        );
    }

    private Document canonicalOutbox(String eventId, String alertId, String status, long revision) {
        Document source = new Document("_id", eventId)
                .append("resource_type", "ALERT")
                .append("resource_id", alertId)
                .append("status", status)
                .append("projection_revision", revision)
                .append("resolution_pending", false);
        if ("PUBLISHED".equals(status)) {
            source.append("publication_confirmation_provenance", "BROKER_ACKNOWLEDGED");
        }
        return source;
    }

    private Document canonicalAlert(String alertId, String eventId, String status, long revision) {
        Document alert = new Document("_id", alertId)
                .append("decisionOutboxEventId", eventId)
                .append("decisionOutboxProjectionRevision", revision)
                .append("decisionOutboxStatus", status);
        if ("PUBLISHED".equals(status)) {
            alert.append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED");
        }
        return alert;
    }

    private String projectionStatus(String sourceStatus) {
        return switch (sourceStatus) {
            case "RECOVERY_REQUIRED" -> "FAILED_TERMINAL";
            default -> sourceStatus;
        };
    }

    private List<String> violations(TransactionalOutboxPersistedContractPreflight.Report report) {
        return report.samples().stream()
                .flatMap(sample -> sample.violations().stream())
                .toList();
    }

    private TransactionalOutboxPersistedContractPreflight.Report blockingReport() {
        return new TransactionalOutboxPersistedContractPreflight.Report(
                1,
                1,
                1,
                List.of(new TransactionalOutboxPersistedContractPreflight.UnsupportedPersistedRecord(
                        "OUTBOX",
                        "record-hash",
                        "PUBLISHED",
                        List.of("PUBLISHED_PROVENANCE_MISSING_OR_INVALID")
                ))
        );
    }
}
