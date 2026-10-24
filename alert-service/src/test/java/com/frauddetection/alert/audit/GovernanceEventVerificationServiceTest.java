package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxStatus;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxPublisher;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRepository;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxClaimStore;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.audit.GovernanceEventVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

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
        when(mongo.count(any(Query.class), eq(AuditEventDocument.class))).thenReturn(1L);
        when(anchors.findByPartitionKeyAndChainPositionIn(eq(audit.partitionKey()), eq(Set.of(1L))))
                .thenReturn(java.util.List.of(AuditAnchorDocument.from("anchor-1", audit)));
        when(auditEvents.findLatestByPartitionKey(audit.partitionKey())).thenReturn(Optional.of(audit));
        when(auditEvents.countByPartitionKey(audit.partitionKey())).thenReturn(1L);
        when(anchors.findLatestByPartitionKey(audit.partitionKey()))
                .thenReturn(Optional.of(AuditAnchorDocument.from("head-anchor", audit)));

        var result = verification.verify(auditor(), KIND, source.id());
        assertThat(result.status()).isEqualTo("LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED");
        assertThat(result.canonicalSha256()).isEqualTo(obligation.getGovernanceEvidenceHash());
        assertThat(result.localAnchorId()).isEqualTo("anchor-1");
        assertThat(result.independentAnchorStatus()).isEqualTo("NOT_VERIFIED");

        when(auditEvents.countByPartitionKey(audit.partitionKey())).thenReturn(0L);
        assertThat(verification.verify(auditor(), KIND, source.id()).reason())
                .isEqualTo("AUDIT_HEAD_BROKEN_OR_INCOMPLETE");
    }

    @Test void changedOperationProofOrContentAndDeletedRecordFailClosed() {
        var source = event("a".repeat(64));
        var obligation = obligation(source);
        when(mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class))
                .thenReturn(obligation);
        when(mongo.findById(source.id(), PolicyTrustChangeAuditDocument.class)).thenReturn(source);
        assertThat(verification.verify(auditor(), KIND, source.id()).status()).isEqualTo("PENDING");

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

    @Test void unauthorizedReadIsDeniedBeforeDatabaseAccess() {
        var ordinary = new PolicyGovernanceActor("reader", Set.of(ModelPolicyAuthority.AUDIT_READ),
                "issuer", "subject");
        assertThatThrownBy(() -> verification.verify(ordinary, KIND, "event-1"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
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
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.empty(), Optional.of(audit));
        doThrow(new IllegalStateException("ack lost")).when(auditService).audit(
                eq(AuditAction.COMMIT_GOVERNANCE_EVENT), eq(AuditResourceType.MODEL_GOVERNANCE_EVENT),
                eq(source.id()), eq("operation-1"), eq(source.actorId()),
                eq(AuditOutcome.SUCCESS), eq(null), eq(null),
                eq("gov:" + obligation.getGovernanceEvidenceHash()));
        var publisher = new WriteActionAuditOutboxPublisher(outbox, claims, auditService,
                auditEvents, Clock.fixed(NOW, ZoneOffset.UTC), "worker", Duration.ofMinutes(5));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(obligation.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PUBLISHED);
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
        when(auditEvents.findByRequestId("gov:" + obligation.getGovernanceEvidenceHash()))
                .thenReturn(Optional.of(audit));
        var publisher = new WriteActionAuditOutboxPublisher(outbox, claims, auditService,
                auditEvents, Clock.fixed(NOW, ZoneOffset.UTC), "worker", Duration.ofMinutes(5));

        assertThat(publisher.publishPending()).isEqualTo(1);
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
