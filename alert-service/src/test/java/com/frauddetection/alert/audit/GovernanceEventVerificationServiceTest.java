package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxStatus;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxPublisher;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRepository;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxClaimStore;
import com.frauddetection.alert.audit.external.GovernanceExternalAnchorVerifier;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.audit.GovernanceEventVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionSignedReviewService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

class GovernanceEventVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final GovernanceEventCommitment.Kind KIND = GovernanceEventCommitment.Kind.TRUST_CHANGE;
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
    private final AuditAnchorRepository anchors = mock(AuditAnchorRepository.class);
    private final GovernanceEventVerificationService verification =
            new GovernanceEventVerificationService(mongo, auditEvents, anchors);

    @Test void genuineEventAndLocalAnchorVerifyWithoutClaimingExternalSignature() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        obligation.setStatus(WriteActionAuditOutboxStatus.PUBLISHED);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        var audit = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionIn(eq(audit.partitionKey()), eq(Set.of(1L))))
                .thenReturn(java.util.List.of(AuditAnchorDocument.from("anchor-1", audit)));
        when(auditEvents.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.of(audit));
        when(anchors.findLatestByPartitionKey(audit.partitionKey()))
                .thenReturn(Optional.of(AuditAnchorDocument.from("head-anchor", audit)));

        var result = verification.verify(auditor(), KIND, source.id());
        assertThat(result.status()).isEqualTo("LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED");
        assertThat(result.canonicalSha256()).isEqualTo(obligation.getGovernanceEvidenceHash());
        assertThat(result.localAnchorId()).isEqualTo("anchor-1");
        assertThat(result.independentAnchorStatus()).isEqualTo("NOT_VERIFIED");
        assertThat(result.eventIntegrity()).isEqualTo(GovernanceEventVerificationService.EventIntegrity.VERIFIED);
        assertThat(result.chainRangeIntegrity())
                .isEqualTo(GovernanceEventVerificationService.ChainRangeIntegrity.NOT_CHECKED);
        assertThat(result.currentAuthorization())
                .isEqualTo(GovernanceEventVerificationService.CurrentAuthorization.NOT_ASSESSED);

        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit.withEventHash("f".repeat(64))), Optional.of(audit));
        assertThat(verification.verify(auditor(), KIND, source.id()).eventIntegrity())
                .isEqualTo(GovernanceEventVerificationService.EventIntegrity.INVALID);

        when(anchors.findLatestByPartitionKey(audit.partitionKey()))
                .thenReturn(Optional.of(new AuditAnchorDocument("wrong-head-anchor", NOW, audit.partitionKey(),
                        "f".repeat(64), audit.previousEventHash(), 1L, "SHA-256")));
        assertThat(verification.verify(auditor(), KIND, source.id()).reason())
                .isEqualTo("AUDIT_HEAD_BROKEN_OR_INCOMPLETE");
    }

    @Test void changedOperationProofOrContentAndDeletedRecordFailClosed() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("OUTCOME_UNKNOWN");

        obligation.setGovernanceOperationId("changed-operation");
        assertThat(verification.verify(auditor(), KIND, source.id()).reason())
                .isEqualTo("EVENT_HASH_MISMATCH");
        obligation.setGovernanceOperationId("operation-1");
        obligation.setGovernanceProofDigest("b".repeat(64));
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("TAMPERED");
        obligation.setGovernanceProofDigest("a".repeat(64));
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class))
                .thenReturn(null);
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("MISSING_EVIDENCE");
    }

    @Test void missingExactRequestUsesBoundedIndexedEvidenceInsteadOfFullChainScan() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        obligation.setStatus(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.empty());

        var result = verification.verify(auditor(), KIND, source.id());

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.canonicalSha256()).isEqualTo(obligation.getGovernanceEvidenceHash());
        verify(auditEvents, never()).findFullChain(anyString(), anyInt());
        verify(auditEvents, never()).countByPartitionKey(anyString());
    }

    @Test void activeAuditLeaseOrPublisherClaimKeepsMissingAppendOutcomeUnknown() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        obligation.setStatus(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.empty());
        var activeLease = new AuditChainLockDocument("source_service:alert-service", "active-writer",
                NOW.plusSeconds(30), 7L);
        var lockRead = new java.util.concurrent.atomic.AtomicInteger();
        when(mongo.findById("source_service:alert-service", AuditChainLockDocument.class))
                .thenAnswer(ignored -> lockRead.getAndIncrement() == 0 ? activeLease : null);

        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("OUTCOME_UNKNOWN");

        var ownerlessActiveLease = new AuditChainLockDocument("source_service:alert-service", null,
                Instant.now().plusSeconds(30), 8L);
        when(mongo.findById("source_service:alert-service", AuditChainLockDocument.class))
                .thenReturn(ownerlessActiveLease);
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("OUTCOME_UNKNOWN");

        obligation.setStatus(WriteActionAuditOutboxStatus.PUBLISHING);
        obligation.setClaimOwner("publisher");
        obligation.setClaimToken("live-token");
        obligation.setClaimedAt(NOW);
        obligation.setClaimExpiresAt(NOW.plusSeconds(30));
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test void missingExactLocalAnchorIsIntegrityFailureEvenWhenEventHashIsValid() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        var audit = AuditEventDocument.from("audit-missing-anchor", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit));
        when(auditEvents.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionIn(audit.partitionKey(), Set.of(1L)))
                .thenReturn(List.of());
        when(anchors.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.empty());

        var result = verification.verify(auditor(), KIND, source.id());

        assertThat(result.status()).isEqualTo("TAMPERED");
        assertThat(result.reason()).isEqualTo("LOCAL_ANCHOR_MISSING_OR_MISMATCHED");
    }

    @Test void unavailableExternalWitnessDoesNotEraseVerifiedLocalAppend() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        var audit = AuditEventDocument.from("audit-local-only", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        var anchor = AuditAnchorDocument.from("anchor-local-only", audit);
        var external = mock(GovernanceExternalAnchorVerifier.class);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class)).thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash())).thenReturn(Optional.of(audit));
        when(auditEvents.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionIn(audit.partitionKey(), Set.of(1L))).thenReturn(List.of(anchor));
        when(anchors.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.of(anchor));
        org.mockito.Mockito.doThrow(new IllegalStateException("witness backend unavailable"))
                .when(external).verify(anchor);
        var service = new GovernanceEventVerificationService(mongo, auditEvents, anchors,
                null, null, external, null);

        var result = service.verify(auditor(), KIND, source.id());

        assertThat(result.auditEventSha256()).isEqualTo(audit.eventHash());
        assertThat(result.chainPosition()).isEqualTo(1L);
        assertThat(result.independentAnchorStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.externalWitness()).isEqualTo(GovernanceEventVerificationService.WitnessAssurance.UNAVAILABLE);
    }

    @Test void unauthorizedReadIsDeniedBeforeDatabaseAccess() {
        var ordinary = new PolicyGovernanceActor("reader", Set.of(ModelPolicyAuthority.AUDIT_READ),
                "issuer", "subject");
        assertThatThrownBy(() -> verification.verify(ordinary, KIND, "event-1"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void signedModelPromotionProofIsReportedSeparatelyFromChainAndAuthorization() {
        var signedReviews = mock(ModelPromotionSignedReviewService.class);
        var service = new GovernanceEventVerificationService(mongo, auditEvents, anchors,
                null, null, null, signedReviews);

        var verified = service.verifyModelPromotionReviewProof(auditor(), "proposal-1");
        assertThat(verified.status()).isEqualTo("HISTORICALLY_VERIFIED");
        assertThat(verified.eventIntegrity()).isEqualTo(GovernanceEventVerificationService.EventIntegrity.NOT_CHECKED);
        assertThat(verified.chainRangeIntegrity())
                .isEqualTo(GovernanceEventVerificationService.ChainRangeIntegrity.NOT_CHECKED);
        assertThat(verified.currentAuthorization())
                .isEqualTo(GovernanceEventVerificationService.CurrentAuthorization.NOT_ASSESSED);
        verify(signedReviews).verifyHistoricalReview(auditor(), "proposal-1");

        doThrow(new SecurityException("historical proof absent"))
                .when(signedReviews).verifyHistoricalReview(auditor(), "proposal-missing");
        assertThat(service.verifyModelPromotionReviewProof(auditor(), "proposal-missing").historicalActionProof())
                .isEqualTo(GovernanceEventVerificationService.HistoricalActionProof.INVALID_OR_UNAVAILABLE);
    }

    @Test void lostAuditAcknowledgementReconcilesExactExistingReceipt() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        var audit = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        var outbox = mock(WriteActionAuditOutboxRepository.class);
        var claims = mock(WriteActionAuditOutboxClaimStore.class);
        var auditService = mock(AuditService.class);
        when(outbox.findPublishable(eq(NOW), any())).thenReturn(List.of(obligation));
        when(claims.claimForPublishing(eq(obligation.getOutboxId()), eq(NOW), eq("worker"),
                eq(Duration.ofMinutes(5)))).thenReturn(Optional.of(obligation));
        when(claims.markPublished(obligation, NOW)).thenReturn(true);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.empty(), Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionBetween(audit.partitionKey(), 1L, 1L, 2))
                .thenReturn(List.of(AuditAnchorDocument.from("anchor-1", audit)));
        doThrow(new IllegalStateException("ack lost")).when(auditService).audit(
                eq(AuditAction.COMMIT_GOVERNANCE_EVENT), eq(AuditResourceType.MODEL_GOVERNANCE_EVENT),
                eq(source.id()), eq("operation-1"), eq(source.actorId()),
                eq(AuditOutcome.SUCCESS), eq(null), eq(null),
                eq("gov:" + obligation.getGovernanceEvidenceHash()));
        var publisher = new WriteActionAuditOutboxPublisher(outbox, claims, auditService,
                auditEvents, anchors, Clock.fixed(NOW, ZoneOffset.UTC), "worker", Duration.ofMinutes(5));

        assertThat(publisher.publishPending()).isEqualTo(1);
        verify(claims).markPublished(obligation, NOW);
    }

    @Test void existingExactReceiptSkipsNewSigningAttempt() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        var audit = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        var outbox = mock(WriteActionAuditOutboxRepository.class);
        var claims = mock(WriteActionAuditOutboxClaimStore.class);
        var auditService = mock(AuditService.class);
        when(outbox.findPublishable(eq(NOW), any())).thenReturn(List.of(obligation));
        when(claims.claimForPublishing(eq(obligation.getOutboxId()), eq(NOW), eq("worker"),
                eq(Duration.ofMinutes(5)))).thenReturn(Optional.of(obligation));
        when(claims.markPublished(obligation, NOW)).thenReturn(true);
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionBetween(audit.partitionKey(), 1L, 1L, 2))
                .thenReturn(List.of(AuditAnchorDocument.from("anchor-1", audit)));
        var publisher = new WriteActionAuditOutboxPublisher(outbox, claims, auditService,
                auditEvents, anchors, Clock.fixed(NOW, ZoneOffset.UTC), "worker", Duration.ofMinutes(5));

        assertThat(publisher.publishPending()).isEqualTo(1);
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void missingLocalAnchorCannotReconcileAuditAppend() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        var audit = AuditEventDocument.from("audit-1", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()),
                AuditAction.COMMIT_GOVERNANCE_EVENT, AuditResourceType.MODEL_GOVERNANCE_EVENT,
                obligation.getResourceId(), NOW, obligation.getCorrelationId(),
                "gov:" + obligation.getGovernanceEvidenceHash(), AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), null, 1);
        var outbox = mock(WriteActionAuditOutboxRepository.class);
        var claims = mock(WriteActionAuditOutboxClaimStore.class);
        var auditService = mock(AuditService.class);
        when(outbox.findPublishable(eq(NOW), any())).thenReturn(List.of(obligation));
        when(claims.claimForPublishing(eq(obligation.getOutboxId()), eq(NOW), eq("worker"),
                eq(Duration.ofMinutes(5)))).thenReturn(Optional.of(obligation));
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit));
        when(anchors.findByPartitionKeyAndChainPositionBetween(audit.partitionKey(), 1L, 1L, 2))
                .thenReturn(List.of());
        var publisher = new WriteActionAuditOutboxPublisher(outbox, claims, auditService,
                auditEvents, anchors, Clock.fixed(NOW, ZoneOffset.UTC), "worker", Duration.ofMinutes(5));

        assertThat(publisher.publishPending()).isZero();
        verify(claims).markFailed(obligation, NOW, "IMMUTABLE_AUDIT_RECEIPT_CONFLICT");
        verify(claims, never()).markPublished(any(), any());
        verify(auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private WriteActionAuditOutboxRecord obligation(PolicyTrustChangeAuditDocument source) {
        GovernanceEventCommitment.record(mongo, KIND, source.id(), source, "CURRENT_INSTITUTION/TEST",
                source.actorId(), source.requestId(), "operation-1", source.assertionSha256(),
                source.requestId(), source.occurredAt());
        var captor = ArgumentCaptor.forClass(WriteActionAuditOutboxRecord.class);
        verify(mongo).insert(captor.capture());
        return captor.getValue();
    }

    private PolicyTrustChangeAuditDocument event(String proofDigest) {
        return new PolicyTrustChangeAuditDocument("event-1", "request-1", "APPROVE",
                "actor-1", "c".repeat(64), proofDigest, "proof-1", NOW);
    }

    private PolicyGovernanceActor auditor() {
        return new PolicyGovernanceActor("auditor", Set.of(ModelPolicyAuthority.AUDIT_READ,
                ModelPolicyAuthority.PRODUCTION), "issuer", "subject");
    }
}
