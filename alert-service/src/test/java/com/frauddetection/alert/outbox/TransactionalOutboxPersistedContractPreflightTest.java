package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.service.FraudDecisionOutboxPublisher;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
    void rejectsMissingOrInvalidAttemptCountersThatCouldBypassRetryBudget() {
        Document missing = canonicalOutbox("event-attempts-missing", "alert-attempts-missing", "PENDING", 0L);
        missing.remove("attempts");
        Document negative = canonicalOutbox("event-attempts-negative", "alert-attempts-negative", "PENDING", 0L)
                .append("attempts", -1);
        Document oversized = canonicalOutbox("event-attempts-oversized", "alert-attempts-oversized", "PENDING", 0L)
                .append("attempts", (long) Integer.MAX_VALUE + 1L);

        assertThat(violations(inspect(missing))).contains("ATTEMPTS_MISSING_OR_INVALID");
        assertThat(violations(inspect(negative))).contains("ATTEMPTS_MISSING_OR_INVALID");
        assertThat(violations(inspect(oversized))).contains("ATTEMPTS_MISSING_OR_INVALID");
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
        Document source = activeOutbox(
                "event-current-claim",
                "alert-current-claim",
                "PROCESSING",
                1,
                0L
        );

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(
                source,
                canonicalAlert("alert-current-claim", "event-current-claim", "PENDING", 0L)
        );

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
        Document pendingDual = canonicalPendingDual("event-dual", "alert-dual", 2L);

        TransactionalOutboxPersistedContractPreflight.Report report = preflight.inspectRawDocuments(
                List.of(ordinary, pendingDual),
                List.of(
                        canonicalAlert("alert-current", "event-current", "PENDING", 0L),
                        canonicalProjectedAlert(pendingDual)
                ),
                10
        );

        assertThat(report.blocksStartup()).isFalse();
        assertThat(report.samples()).isEmpty();
    }

    @Test
    void scansAllDocumentsBeyondDiagnosticSampleLimit() {
        List<Document> sources = new ArrayList<>();
        List<Document> alerts = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String eventId = "event-sample-" + index;
            String alertId = "alert-sample-" + index;
            Document source = canonicalOutbox(eventId, alertId, "PENDING", 0L);
            source.remove("projection_revision");
            sources.add(source);
            alerts.add(canonicalAlert(alertId, eventId, "PENDING", 0L));
        }

        TransactionalOutboxPersistedContractPreflight.Report report =
                preflight.inspectRawDocuments(sources, alerts, 1);

        assertThat(report.unsupportedUnfinishedCount()).isEqualTo(4);
        assertThat(report.samples()).hasSize(1);
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
    void rejectsPendingSourceWithPublishedProjection() {
        Document source = canonicalOutbox("event-false-published", "alert-false-published", "PENDING", 0L);
        Document alert = canonicalAlert("alert-false-published", "event-false-published", "PUBLISHED", 0L);
        alert.remove("decisionOutboxPublishedAt");
        alert.remove("decisionOutboxPublicationConfirmationProvenance");

        assertThat(violations(inspect(source, alert))).contains("ALERT_STATUS_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsPendingSourceWithBrokerAcknowledgedPublishedProjection() {
        Document source = canonicalOutbox("event-false-broker", "alert-false-broker", "PENDING", 0L);
        Document alert = canonicalAlert("alert-false-broker", "event-false-broker", "PUBLISHED", 0L);
        alert.remove("decisionOutboxPublishedAt");

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_STATUS_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void rejectsPendingSourceWithFabricatedPublishedTimestamp() {
        Document source = canonicalOutbox("event-false-time", "alert-false-time", "PENDING", 0L);
        Document alert = canonicalAlert("alert-false-time", "event-false-time", "PUBLISHED", 0L);
        alert.remove("decisionOutboxPublicationConfirmationProvenance");

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_STATUS_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void rejectsPendingPublishedProjectionDespiteReconciliationMarker() {
        Document source = canonicalOutbox("event-false-marked", "alert-false-marked", "PENDING", 0L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-false-marked", "event-false-marked", "PUBLISHED", 0L)
                .append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED")
                .append("decisionOutboxPublishedAt", Instant.parse("2026-10-01T10:00:00Z"));

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source, alert);

        assertThat(report.blocksStartup()).isTrue();
        assertThat(violations(report)).contains("ALERT_STATUS_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsNonCanonicalPendingAttemptsAndProjectionMetadata() {
        Document source = canonicalOutbox("event-pending-corrupt", "alert-pending-corrupt", "PENDING", 0L)
                .append("attempts", 1);
        Document alert = canonicalAlert("alert-pending-corrupt", "event-pending-corrupt", "PENDING", 0L)
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxPublishedAt", Instant.parse("2026-10-01T10:00:00Z"))
                .append("decisionOutboxResolutionApprovedBy", "fabricated-approver");

        assertThat(violations(inspect(source, alert))).contains(
                "PENDING_SOURCE_NOT_CANONICAL_INITIAL_STATE",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxAttempts",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxResolutionApprovedBy"
        );
    }

    @Test
    void acceptsProcessingWithInitialPendingProjection() {
        Document source = activeOutbox("event-processing-initial", "alert-processing-initial", "PROCESSING", 1, 0L);
        Document alert = canonicalAlert("alert-processing-initial", "event-processing-initial", "PENDING", 0L);

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void acceptsProcessingWithLaggingFailedRetryableProjection() {
        Document source = activeOutbox("event-processing-retry", "alert-processing-retry", "PROCESSING", 2, 1L);
        Document alert = canonicalAlert(
                "alert-processing-retry",
                "event-processing-retry",
                "FAILED_RETRYABLE",
                1L
        ).append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE");

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void acceptsPublishAttemptedWithLaggingFailedRetryableProjection() {
        Document source = activeOutbox(
                "event-attempted-retry",
                "alert-attempted-retry",
                "PUBLISH_ATTEMPTED",
                2,
                1L
        );
        Document alert = canonicalAlert(
                "alert-attempted-retry",
                "event-attempted-retry",
                "FAILED_RETRYABLE",
                1L
        ).append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE");

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void rejectsProcessingPredecessorWithFabricatedManualApproval() {
        Document source = activeOutbox("event-forged-approval", "alert-forged-approval", "PROCESSING", 2, 1L);
        Document alert = canonicalAlert("alert-forged-approval", "event-forged-approval", "FAILED_RETRYABLE", 1L)
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE")
                .append("decisionOutboxResolutionApprovedBy", "forged-operator")
                .append("decisionOutboxResolutionApprovedAt", Instant.parse("2026-10-01T10:00:00Z"));

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxResolutionApprovedBy",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxResolutionApprovedAt"
        );
    }

    @Test
    void rejectsPublishAttemptedPredecessorWithFabricatedManualEvidence() {
        Document source = activeOutbox(
                "event-forged-evidence",
                "alert-forged-evidence",
                "PUBLISH_ATTEMPTED",
                2,
                1L
        );
        Document alert = canonicalAlert("alert-forged-evidence", "event-forged-evidence", "FAILED_RETRYABLE", 1L)
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE")
                .append("decisionOutboxResolutionEvidenceReference", "forged-evidence");

        assertThat(violations(inspect(source, alert)))
                .contains("ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxResolutionEvidenceReference");
    }

    @Test
    void rejectsInitialPendingPredecessorWithResolutionPending() {
        Document source = activeOutbox("event-forged-pending", "alert-forged-pending", "PROCESSING", 1, 0L);
        Document alert = canonicalAlert("alert-forged-pending", "event-forged-pending", "PENDING", 0L)
                .append("decisionOutboxResolutionPending", true);

        assertThat(violations(inspect(source, alert)))
                .contains("ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxResolutionPending");
    }

    @Test
    void rejectsLossyOrOutOfRangeProjectedAttemptsForEveryProjectionStatus() {
        for (Object invalidAttempts : List.of(
                1.9d,
                0.9d,
                new Decimal128(new BigDecimal("1.9")),
                -1,
                (long) Integer.MAX_VALUE + 1L
        )) {
            Document source = canonicalOutbox("event-invalid-attempts", "alert-invalid-attempts", "PUBLISHED", 1L);
            Document alert = canonicalAlert("alert-invalid-attempts", "event-invalid-attempts", "PUBLISHED", 1L)
                    .append("decisionOutboxAttempts", invalidAttempts);

            assertThat(violations(inspect(source, alert)))
                    .contains("ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxAttempts");
        }
    }

    @Test
    void acceptsSupportedLongProjectedAttempts() {
        Document source = canonicalOutbox("event-long-attempts", "alert-long-attempts", "PUBLISHED", 1L)
                .append("attempts", 1);
        Document alert = canonicalAlert("alert-long-attempts", "event-long-attempts", "PUBLISHED", 1L)
                .append("decisionOutboxAttempts", 1L);

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void rejectsActiveSourceWithFailedRetryableProjectionWithoutPriorFailureHistory() {
        Document source = activeOutbox("event-no-history", "alert-no-history", "PROCESSING", 1, 0L);
        Document alert = canonicalAlert("alert-no-history", "event-no-history", "FAILED_RETRYABLE", 0L);

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxAttempts",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxProjectionRevision",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxFailureReason"
        );
    }

    @Test
    void rejectsPublishAttemptedWithImpossibleFailedRetryableHistory() {
        Document source = activeOutbox("event-impossible-history", "alert-impossible-history",
                "PUBLISH_ATTEMPTED", 2, 1L);
        Document alert = canonicalAlert("alert-impossible-history", "event-impossible-history",
                "FAILED_RETRYABLE", 1L)
                .append("decisionOutboxAttempts", 2)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "DIFFERENT_REASON");

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxAttempts",
                "ALERT_PROJECTION_SEMANTIC_MISMATCH_decisionOutboxFailureReason"
        );
    }

    @Test
    void acceptsExpiredLeaseReclaimWithOlderFailedRetryableAttempt() {
        Document source = activeOutbox("event-reclaimed", "alert-reclaimed", "PROCESSING", 4, 1L);
        Document alert = canonicalAlert("alert-reclaimed", "event-reclaimed", "FAILED_RETRYABLE", 1L)
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE");

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void acceptsScheduledRevisionLagWithLegalFailedRetryableHistory() {
        Document source = activeOutbox("event-lagging-history", "alert-lagging-history", "PROCESSING", 3, 2L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-lagging-history", "event-lagging-history", "FAILED_RETRYABLE", 1L)
                .append("decisionOutboxAttempts", 1)
                .append("decisionOutboxLastError", "BROKER_UNAVAILABLE")
                .append("decisionOutboxFailureReason", "BROKER_UNAVAILABLE");

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void rejectsProcessingWithTerminalFailureProjection() {
        assertIllegalPreConfirmationProjection("PROCESSING", "FAILED_TERMINAL");
    }

    @Test
    void rejectsPublishAttemptedWithTerminalFailureProjection() {
        assertIllegalPreConfirmationProjection("PUBLISH_ATTEMPTED", "FAILED_TERMINAL");
    }

    @Test
    void rejectsProcessingWithConfirmationUnknownProjection() {
        assertIllegalPreConfirmationProjection("PROCESSING", "PUBLISH_CONFIRMATION_UNKNOWN");
    }

    @Test
    void rejectsPublishAttemptedWithConfirmationUnknownProjection() {
        assertIllegalPreConfirmationProjection("PUBLISH_ATTEMPTED", "PUBLISH_CONFIRMATION_UNKNOWN");
    }

    @Test
    void rejectsEqualRevisionWithDifferentStableStatus() {
        Document source = canonicalOutbox("event-status", "alert-status", "PUBLISHED", 5L);
        Document alert = canonicalAlert("alert-status", "event-status", "FAILED_TERMINAL", 5L);

        TransactionalOutboxPersistedContractPreflight.Report report = inspect(source, alert);

        assertThat(violations(report)).contains("ALERT_STATUS_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsEqualRevisionWithContradictoryPublicationProvenance() {
        Document source = canonicalOutbox("event-provenance", "alert-provenance", "PUBLISHED", 5L);
        Document alert = canonicalAlert("alert-provenance", "event-provenance", "PUBLISHED", 5L)
                .append("decisionOutboxPublicationConfirmationProvenance", "MANUAL_DUAL_CONTROL_ATTESTED");

        assertThat(violations(inspect(source, alert)))
                .contains("ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsPublishedManualSourceWithBrokerProjectionDespiteReconciliationMarkers() {
        Document source = canonicalDualPublished("event-manual-provenance", "alert-manual-provenance", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalProjectedAlert(source)
                .append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED")
                .append("decisionOutboxPublishedAt", Instant.parse("2026-10-01T10:00:01Z"));

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void acceptsMatchingPublishedEvidenceWithScheduledReconciliation() {
        Document source = canonicalDualPublished("event-matching-published", "alert-matching-published", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));

        assertThat(inspect(source, canonicalProjectedAlert(source)).blocksStartup()).isFalse();
    }

    @Test
    void rejectsPublishedSourceWithoutCanonicalPublicationTimestamp() {
        Document source = canonicalOutbox("event-no-time", "alert-no-time", "PUBLISHED", 1L);
        source.remove("published_at");

        assertThat(violations(inspect(source))).contains("PUBLISHED_AT_MISSING_OR_INVALID");
    }

    @Test
    void rejectsPublishedSourceWithInvalidPublicationTimestamp() {
        Document source = canonicalOutbox("event-bad-time", "alert-bad-time", "PUBLISHED", 1L)
                .append("published_at", "not-an-instant");

        assertThat(violations(inspect(source))).contains("PUBLISHED_AT_MISSING_OR_INVALID");
    }

    @Test
    void rejectsBrokerAcknowledgementWithManualResolutionMetadata() {
        Document source = canonicalOutbox("event-broker-manual", "alert-broker-manual", "PUBLISHED", 1L)
                .append("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .append("resolution_request_id", "request-1");

        assertThat(violations(inspect(source))).contains("BROKER_PROVENANCE_HAS_MANUAL_METADATA");
    }

    @Test
    void rejectsPublishedDualControlWithRecoveryRequiredOutcome() {
        Document source = canonicalDualPublished("event-outcome", "alert-outcome", 2L)
                .append("resolution_proposed_outcome", "RECOVERY_REQUIRED");

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_SEMANTICS_INVALID");
    }

    @Test
    void rejectsDualControlWhenRequesterApprovesOwnRequest() {
        Document source = canonicalDualPublished("event-same-actor", "alert-same-actor", 2L)
                .append("resolution_approved_by", "requester");

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_SEMANTICS_INVALID");
    }

    @Test
    void rejectsDualControlApprovalBeforeRequest() {
        Document source = canonicalDualPublished("event-time-order", "alert-time-order", 2L)
                .append("resolution_approved_at", Instant.parse("2026-10-01T09:59:00Z"));

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_SEMANTICS_INVALID");
    }

    @Test
    void rejectsPendingDualControlWithApprovalMetadata() {
        Document source = canonicalPendingDual("event-pending-approval", "alert-pending-approval", 2L)
                .append("resolution_approved_by", "approver");

        assertThat(violations(inspect(source))).contains("PENDING_RESOLUTION_HAS_APPROVAL_METADATA");
    }

    @Test
    void rejectsCompletedDualControlWithoutApprovalEvidence() {
        Document source = canonicalDualPublished("event-no-approval", "alert-no-approval", 2L);
        source.remove("resolution_approval_evidence_reference");

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_APPROVAL_INCOMPLETE");
    }

    @Test
    void rejectsResolutionEvidenceFingerprintMismatch() {
        Document source = canonicalDualPublished("event-bad-fingerprint", "alert-bad-fingerprint", 2L)
                .append("resolution_evidence_fingerprint", "wrong-fingerprint");

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_SEMANTICS_INVALID");
    }

    @Test
    void acceptsRecoveryRequiredToFailedTerminalProjectionMapping() {
        Document source = canonicalSingleRecoveryRequired(
                "event-recovery",
                "alert-recovery",
                3L,
                ResolutionEvidenceType.RUNBOOK_STEP
        );
        Document alert = canonicalProjectedAlert(source);

        assertThat(alert.getString("decisionOutboxStatus")).isEqualTo("FAILED_TERMINAL");
        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void acceptsPendingAndApprovedRecoveryRequiredWithTicketEvidence() {
        Document pending = canonicalPendingDual(
                "event-pending-ticket",
                "alert-pending-ticket",
                2L,
                "RECOVERY_REQUIRED",
                ResolutionEvidenceType.TICKET
        );
        Document approved = canonicalDualRecoveryRequired(
                "event-approved-ticket",
                "alert-approved-ticket",
                3L,
                ResolutionEvidenceType.TICKET
        );

        assertThat(inspect(pending, canonicalProjectedAlert(pending)).blocksStartup()).isFalse();
        assertThat(inspect(approved, canonicalProjectedAlert(approved)).blocksStartup()).isFalse();
    }

    @Test
    void rejectsRecoveryRequiredWithMalformedFingerprintOrMissingOperator() {
        Document malformed = canonicalSingleRecoveryRequired(
                "event-malformed",
                "alert-malformed",
                3L,
                ResolutionEvidenceType.RUNBOOK_STEP
        ).append("resolution_evidence_fingerprint", "malformed");
        Document missingOperator = canonicalSingleRecoveryRequired(
                "event-missing-operator",
                "alert-missing-operator",
                3L,
                ResolutionEvidenceType.TICKET
        );
        missingOperator.remove("resolution_approved_by");

        assertThat(violations(inspect(malformed))).contains("SINGLE_CONTROL_SEMANTICS_INVALID");
        assertThat(violations(inspect(missingOperator))).contains("SINGLE_CONTROL_APPROVAL_INCOMPLETE");
    }

    @Test
    void rejectsMetadataFreeRecoveryRequired() {
        Document source = canonicalOutbox("event-no-provenance", "alert-no-provenance", "RECOVERY_REQUIRED", 1L)
                .append("last_error", "MANUAL_RECOVERY_REQUIRED");

        assertThat(violations(inspect(source))).contains("RECOVERY_REQUIRED_PROVENANCE_MISSING");
    }

    @Test
    void rejectsPublishedManualResolutionWithTicketEvidence() {
        Document source = canonicalDualPublished("event-ticket-published", "alert-ticket-published", 2L);
        ResolutionEvidenceReference ticket = new ResolutionEvidenceReference(
                ResolutionEvidenceType.TICKET,
                "incident=INC-42",
                Instant.parse("2026-10-01T10:04:00Z"),
                "request-verifier"
        );
        source.append("resolution_evidence_type", ticket.type().name())
                .append("resolution_evidence_reference", ticket.reference())
                .append("resolution_evidence_verified_at", ticket.verifiedAt())
                .append("resolution_evidence_verified_by", ticket.verifiedBy())
                .append("resolution_evidence_fingerprint", RegulatedMutationIntentHasher.hash(ticket));

        assertThat(violations(inspect(source))).contains("DUAL_CONTROL_SEMANTICS_INVALID");
    }

    @Test
    void acceptsScheduledRecoverableProjectionDivergence() {
        Document source = canonicalOutbox("event-scheduled", "alert-scheduled", "PUBLISHED", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-scheduled", "event-scheduled", "FAILED_TERMINAL", 5L);

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void rejectsPublicationProvenanceOnLaggingNonPublishedProjection() {
        Document source = canonicalDualPublished("event-lagging-provenance", "alert-lagging-provenance", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert(
                "alert-lagging-provenance",
                "event-lagging-provenance",
                "FAILED_TERMINAL",
                4L
        ).append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED");

        assertThat(violations(inspect(source, alert)))
                .contains("ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsPublishedAtOnLaggingNonPublishedProjection() {
        Document source = canonicalDualPublished("event-lagging-time", "alert-lagging-time", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-lagging-time", "event-lagging-time", "FAILED_TERMINAL", 4L)
                .append("decisionOutboxPublishedAt", Instant.parse("2026-10-01T10:00:01Z"));

        assertThat(violations(inspect(source, alert))).contains("ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE");
    }

    @Test
    void rejectsFalsePublicationHiddenBehindReconciliationMarker() {
        Document source = canonicalOutbox("event-false-marker", "alert-false-marker", "FAILED_TERMINAL", 5L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-false-marker", "event-false-marker", "PUBLISHED", 4L);

        assertThat(violations(inspect(source, alert))).contains(
                "ALERT_STATUS_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLISHED_AT_DOES_NOT_MATCH_SOURCE",
                "ALERT_PUBLICATION_PROVENANCE_DOES_NOT_MATCH_SOURCE"
        );
    }

    @Test
    void acceptsScheduledLaggingProjectionForRetryableSource() {
        Document source = canonicalOutbox("event-retry-scheduled", "alert-retry-scheduled", "FAILED_RETRYABLE", 2L)
                .append("projection_mismatch", true)
                .append("projection_reconcile_after", Instant.parse("2026-10-01T10:10:00Z"));
        Document alert = canonicalAlert("alert-retry-scheduled", "event-retry-scheduled", "PENDING", 1L);

        assertThat(inspect(source, alert).blocksStartup()).isFalse();
    }

    @Test
    void acceptsFullyCanonicalPublishedDocumentPair() {
        Document source = canonicalDualPublished("event-canonical", "alert-canonical", 7L);

        assertThat(inspect(source, canonicalProjectedAlert(source)).blocksStartup()).isFalse();
    }

    @Test
    void rawInspectionIgnoresDefaultOutboxFieldsOnUndecidedAlert() {
        Document alert = new Document("_id", "alert-without-decision")
                .append("decisionOutboxProjectionRevision", 0L)
                .append("decisionOutboxAttempts", 0)
                .append("decisionOutboxResolutionPending", false);

        TransactionalOutboxPersistedContractPreflight.Report report =
                preflight.inspectRawDocuments(List.of(), List.of(alert), 10);

        assertThat(report.blocksStartup()).isFalse();
        assertThat(report.unsupportedAlertProjectionCount()).isZero();
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
    void preflightTimeoutFailsStartupAndCannotMarkRuntimeReady() {
        TransactionalOutboxPersistedContractPreflight mockedPreflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(mockedPreflight.inspect(25)).thenThrow(
                new IllegalStateException("Transactional outbox persisted-contract startup validation budget exceeded")
        );
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(mockedPreflight, readiness);

        assertThatThrownBy(() -> guard.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validation budget exceeded");
        readiness.onApplicationEvent(mock(org.springframework.boot.context.event.ApplicationReadyEvent.class));

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void aggregationFailureIsPropagatedInsteadOfBecomingSuccessfulEmptyReport() {
        org.springframework.data.mongodb.core.MongoTemplate mongoTemplate =
                mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCollection<Document> collection = mock(com.mongodb.client.MongoCollection.class);
        when(mongoTemplate.getCollection(TransactionalOutboxPersistedContractPreflight.COLLECTION))
                .thenReturn(collection);
        when(collection.aggregate(org.mockito.ArgumentMatchers.<org.bson.conversions.Bson>anyList()))
                .thenThrow(new IllegalStateException("aggregation unavailable"));

        TransactionalOutboxPersistedContractPreflight failingPreflight =
                new TransactionalOutboxPersistedContractPreflight(mongoTemplate);

        assertThatThrownBy(() -> failingPreflight.inspect(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregation unavailable");
    }

    @Test
    void successfulPreflightDoesNotOpenRuntimeBarrierBeforeApplicationReady() throws Exception {
        TransactionalOutboxPersistedContractPreflight mockedPreflight =
                mock(TransactionalOutboxPersistedContractPreflight.class);
        when(mockedPreflight.inspect(25)).thenReturn(
                new TransactionalOutboxPersistedContractPreflight.Report(0, 0, 0, List.of()));
        TransactionalOutboxRuntimeReadiness readiness = new TransactionalOutboxRuntimeReadiness();
        TransactionalOutboxPersistedContractStartupGuard guard =
                new TransactionalOutboxPersistedContractStartupGuard(mockedPreflight, readiness);

        guard.run(mock(ApplicationArguments.class));

        assertThat(readiness.isReady()).isFalse();
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

    private TransactionalOutboxPersistedContractPreflight.Report inspect(Document source, Document alert) {
        return preflight.inspectRawDocuments(List.of(source), List.of(alert), 10);
    }

    private void assertIllegalPreConfirmationProjection(String sourceStatus, String projectionStatus) {
        String suffix = sourceStatus.toLowerCase() + "-" + projectionStatus.toLowerCase();
        Document source = activeOutbox("event-" + suffix, "alert-" + suffix, sourceStatus, 2, 1L);
        Document alert = canonicalAlert("alert-" + suffix, "event-" + suffix, projectionStatus, 1L);

        assertThat(violations(inspect(source, alert))).contains("ALERT_STATUS_DOES_NOT_MATCH_SOURCE");
    }

    private Document canonicalPendingDual(String eventId, String alertId, long revision) {
        return canonicalPendingDual(
                eventId,
                alertId,
                revision,
                "PUBLISHED",
                ResolutionEvidenceType.BROKER_OFFSET
        );
    }

    private Document canonicalPendingDual(
            String eventId,
            String alertId,
            long revision,
            String outcome,
            ResolutionEvidenceType evidenceType
    ) {
        Instant requestedAt = Instant.parse("2026-10-01T10:05:00Z");
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                evidenceType,
                "evidence=request",
                requestedAt.minusSeconds(1),
                "request-verifier"
        );
        return canonicalOutbox(eventId, alertId, "PUBLISH_CONFIRMATION_UNKNOWN", revision)
                .append("resolution_pending", true)
                .append("resolution_control_mode", "DUAL_CONTROL_REQUESTED")
                .append("resolution_request_id", "request-1")
                .append("resolution_proposed_outcome", outcome)
                .append("resolution_requested_by", "requester")
                .append("resolution_requested_at", requestedAt)
                .append("resolution_request_reason", "verified broker evidence")
                .append("resolution_evidence_type", evidence.type().name())
                .append("resolution_evidence_reference", evidence.reference())
                .append("resolution_evidence_verified_at", evidence.verifiedAt())
                .append("resolution_evidence_verified_by", evidence.verifiedBy())
                .append("resolution_evidence_fingerprint", RegulatedMutationIntentHasher.hash(evidence));
    }

    private Document canonicalDualRecoveryRequired(
            String eventId,
            String alertId,
            long revision,
            ResolutionEvidenceType evidenceType
    ) {
        Instant requestedAt = Instant.parse("2026-10-01T10:05:00Z");
        Instant approvedAt = Instant.parse("2026-10-01T10:06:00Z");
        ResolutionEvidenceReference requestEvidence = new ResolutionEvidenceReference(
                evidenceType, "evidence=request", requestedAt.minusSeconds(1), "request-verifier");
        ResolutionEvidenceReference approvalEvidence = new ResolutionEvidenceReference(
                evidenceType, "evidence=approval", approvedAt.minusSeconds(1), "approval-verifier");
        return canonicalOutbox(eventId, alertId, "RECOVERY_REQUIRED", revision)
                .append("last_error", "MANUAL_RECOVERY_REQUIRED")
                .append("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .append("resolution_request_id", "request-1")
                .append("resolution_proposed_outcome", "RECOVERY_REQUIRED")
                .append("resolution_requested_by", "requester")
                .append("resolution_requested_at", requestedAt)
                .append("resolution_request_reason", "request reason")
                .append("resolution_evidence_type", requestEvidence.type().name())
                .append("resolution_evidence_reference", requestEvidence.reference())
                .append("resolution_evidence_verified_at", requestEvidence.verifiedAt())
                .append("resolution_evidence_verified_by", requestEvidence.verifiedBy())
                .append("resolution_evidence_fingerprint", RegulatedMutationIntentHasher.hash(requestEvidence))
                .append("resolution_approved_by", "approver")
                .append("resolution_approved_at", approvedAt)
                .append("resolution_approval_reason", "approval reason")
                .append("resolution_approval_evidence_type", approvalEvidence.type().name())
                .append("resolution_approval_evidence_reference", approvalEvidence.reference())
                .append("resolution_approval_evidence_verified_at", approvalEvidence.verifiedAt())
                .append("resolution_approval_evidence_verified_by", approvalEvidence.verifiedBy())
                .append("resolution_approval_evidence_fingerprint", RegulatedMutationIntentHasher.hash(approvalEvidence));
    }

    private Document canonicalSingleRecoveryRequired(
            String eventId,
            String alertId,
            long revision,
            ResolutionEvidenceType evidenceType
    ) {
        Instant approvedAt = Instant.parse("2026-10-01T10:06:00Z");
        ResolutionEvidenceReference evidence = new ResolutionEvidenceReference(
                evidenceType, "evidence=single", approvedAt.minusSeconds(1), "evidence-verifier");
        return canonicalOutbox(eventId, alertId, "RECOVERY_REQUIRED", revision)
                .append("last_error", "MANUAL_RECOVERY_REQUIRED")
                .append("resolution_control_mode", "SINGLE_CONTROL_OPERATOR_ATTESTED")
                .append("resolution_proposed_outcome", "RECOVERY_REQUIRED")
                .append("resolution_approved_by", "operator")
                .append("resolution_approved_at", approvedAt)
                .append("resolution_approval_reason", "recovery required")
                .append("resolution_evidence_type", evidence.type().name())
                .append("resolution_evidence_reference", evidence.reference())
                .append("resolution_evidence_verified_at", evidence.verifiedAt())
                .append("resolution_evidence_verified_by", evidence.verifiedBy())
                .append("resolution_evidence_fingerprint", RegulatedMutationIntentHasher.hash(evidence));
    }

    private Document canonicalDualPublished(String eventId, String alertId, long revision) {
        Instant requestedAt = Instant.parse("2026-10-01T10:05:00Z");
        Instant approvedAt = Instant.parse("2026-10-01T10:06:00Z");
        ResolutionEvidenceReference requestEvidence =
                evidence("partition=1,offset=42", requestedAt.minusSeconds(1), "request-verifier");
        ResolutionEvidenceReference approvalEvidence =
                evidence("partition=1,offset=43", approvedAt.minusSeconds(1), "approval-verifier");
        return canonicalOutbox(eventId, alertId, "PUBLISHED", revision)
                .append("publication_confirmation_provenance", "MANUAL_DUAL_CONTROL_ATTESTED")
                .append("resolution_control_mode", "DUAL_CONTROL_APPROVED")
                .append("resolution_request_id", "request-1")
                .append("resolution_proposed_outcome", "PUBLISHED")
                .append("resolution_requested_by", "requester")
                .append("resolution_requested_at", requestedAt)
                .append("resolution_request_reason", "request reason")
                .append("resolution_evidence_type", requestEvidence.type().name())
                .append("resolution_evidence_reference", requestEvidence.reference())
                .append("resolution_evidence_verified_at", requestEvidence.verifiedAt())
                .append("resolution_evidence_verified_by", requestEvidence.verifiedBy())
                .append("resolution_evidence_fingerprint", RegulatedMutationIntentHasher.hash(requestEvidence))
                .append("resolution_approved_by", "approver")
                .append("resolution_approved_at", approvedAt)
                .append("resolution_approval_reason", "approval reason")
                .append("resolution_approval_evidence_type", approvalEvidence.type().name())
                .append("resolution_approval_evidence_reference", approvalEvidence.reference())
                .append("resolution_approval_evidence_verified_at", approvalEvidence.verifiedAt())
                .append("resolution_approval_evidence_verified_by", approvalEvidence.verifiedBy())
                .append("resolution_approval_evidence_fingerprint", RegulatedMutationIntentHasher.hash(approvalEvidence));
    }

    private ResolutionEvidenceReference evidence(String reference, Instant verifiedAt, String verifiedBy) {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                reference,
                verifiedAt,
                verifiedBy
        );
    }

    private Document canonicalProjectedAlert(Document source) {
        TransactionalOutboxRecordDocument record = OutboxAlertProjectionPolicy.persistedRecord(source);
        Document set = OutboxAlertProjectionPolicy.recovery(record).update().getUpdateObject().get("$set", Document.class);
        Document alert = new Document("_id", source.getString("resource_id"));
        alert.putAll(set);
        return alert;
    }

    private Document canonicalOutbox(String eventId, String alertId, String status, long revision) {
        Document source = new Document("_id", eventId)
                .append("resource_type", "ALERT")
                .append("resource_id", alertId)
                .append("status", status)
                .append("attempts", 0)
                .append("projection_revision", revision)
                .append("resolution_pending", false);
        if ("PUBLISHED".equals(status)) {
            source.append("publication_confirmation_provenance", "BROKER_ACKNOWLEDGED")
                    .append("published_at", Instant.parse("2026-10-01T10:00:00Z"));
        }
        return source;
    }

    private Document activeOutbox(
            String eventId,
            String alertId,
            String status,
            int attempts,
            long revision
    ) {
        return canonicalOutbox(eventId, alertId, status, revision)
                .append("attempts", attempts)
                .append("lease_owner", "publisher-1")
                .append("lease_claim_token", "claim-1")
                .append("lease_expires_at", Instant.parse("2026-10-01T10:05:00Z"));
    }

    private Document canonicalAlert(String alertId, String eventId, String status, long revision) {
        Document alert = new Document("_id", alertId)
                .append("decisionOutboxEventId", eventId)
                .append("decisionOutboxProjectionRevision", revision)
                .append("decisionOutboxStatus", status)
                .append("decisionOutboxAttempts", 0);
        if ("PUBLISHED".equals(status)) {
            alert.append("decisionOutboxPublicationConfirmationProvenance", "BROKER_ACKNOWLEDGED")
                    .append("decisionOutboxPublishedAt", Instant.parse("2026-10-01T10:00:00Z"));
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
