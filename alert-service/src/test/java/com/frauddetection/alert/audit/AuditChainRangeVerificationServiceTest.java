package com.frauddetection.alert.audit;

import com.frauddetection.alert.governance.audit.AuditChainRangeVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditChainRangeVerificationServiceTest {
    private static final String PARTITION = "source_service:alert-service";
    private final AuditEventRepository events = mock(AuditEventRepository.class);
    private final AuditAnchorRepository anchors = mock(AuditAnchorRepository.class);
    private final List<AuditEventDocument> chain = chain();

    @Test
    void verifiesEveryEventAndAnchorInInclusiveRangeAgainstStableBoundary() {
        stubStore(chain);

        var result = service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash());

        assertThat(result.chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.CHAIN_RANGE_VERIFIED);
        assertThat(result.checkedEvents()).isEqualTo(3);
        assertThat(result.concurrentAppendObserved()).isFalse();
    }

    @Test
    void intermediateTamperingAndMissingPositionNeverVerifyRange() {
        var tampered = new java.util.ArrayList<>(chain);
        tampered.set(1, tampered.get(1).withEventHash("f".repeat(64)));
        stubStore(tampered);
        assertThat(service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.INVALID);

        stubStore(List.of(chain.get(0), chain.get(2), chain.get(3)));
        assertThat(service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.TRUNCATED);
    }

    @Test
    void wrongPreviousHashAndAlteredAnchorFailClosed() {
        var brokenLink = new java.util.ArrayList<>(chain);
        var original = chain.get(1);
        var changed = AuditEventDocument.from(original.auditId(), new AuditEvent(
                new AuditActor(original.actorId(), Set.copyOf(original.actorRoles()),
                        Set.copyOf(original.actorAuthorities())), original.action(), original.resourceType(),
                original.resourceId(), original.createdAt(), original.correlationId(), original.requestId(),
                original.outcome(), original.failureCategory(), original.failureReason(), original.metadataSummary()),
                "e".repeat(64), 2);
        brokenLink.set(1, changed);
        brokenLink.set(2, AuditEventDocument.from(chain.get(2).auditId(), new AuditEvent(
                new AuditActor(chain.get(2).actorId(), Set.copyOf(chain.get(2).actorRoles()),
                        Set.copyOf(chain.get(2).actorAuthorities())), chain.get(2).action(),
                chain.get(2).resourceType(), chain.get(2).resourceId(), chain.get(2).createdAt(),
                chain.get(2).correlationId(), chain.get(2).requestId(), chain.get(2).outcome(),
                chain.get(2).failureCategory(), chain.get(2).failureReason(), chain.get(2).metadataSummary()),
                changed.eventHash(), 3));
        brokenLink.set(3, AuditEventDocument.from(chain.get(3).auditId(), new AuditEvent(
                new AuditActor(chain.get(3).actorId(), Set.copyOf(chain.get(3).actorRoles()),
                        Set.copyOf(chain.get(3).actorAuthorities())), chain.get(3).action(),
                chain.get(3).resourceType(), chain.get(3).resourceId(), chain.get(3).createdAt(),
                chain.get(3).correlationId(), chain.get(3).requestId(), chain.get(3).outcome(),
                chain.get(3).failureCategory(), chain.get(3).failureReason(), chain.get(3).metadataSummary()),
                brokenLink.get(2).eventHash(), 4));
        stubStore(brokenLink);
        assertThat(service(5).verify(auditor(), 1, 3, 3L, brokenLink.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.INVALID);

        stubStore(chain);
        when(anchors.findByPartitionKeyAndChainPositionBetween(PARTITION, 1L, 3L, 4))
                .thenReturn(List.of(AuditAnchorDocument.from("anchor-1", chain.get(0)),
                        new AuditAnchorDocument("altered", Instant.now(), PARTITION, "f".repeat(64),
                                chain.get(1).previousEventHash(), 2, "SHA-256"),
                        AuditAnchorDocument.from("anchor-3", chain.get(2))));
        assertThat(service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.INVALID);
    }

    @Test
    void duplicatePositionAndInconsistentHeadFailClosed() {
        stubStore(chain);
        when(events.findByPartitionKeyAndChainPositionBetween(PARTITION, 1L, 3L, 4))
                .thenReturn(List.of(chain.get(0), chain.get(1), chain.get(1), chain.get(2)));
        assertThat(service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.INVALID);

        stubStore(chain);
        when(events.countByPartitionKey(PARTITION)).thenReturn(3L);
        assertThat(service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash()).chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.UNSTABLE_BOUNDARY);
    }

    @Test
    void rangeLimitAndProductionAuditAuthorityAreEnforced() {
        var tooLong = service(2).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash());
        assertThat(tooLong.chainRangeIntegrity()).isEqualTo(AuditChainRangeVerificationService.Status.RANGE_TOO_LARGE);
        var ordinaryAuditReader = new PolicyGovernanceActor("reader", Set.of(ModelPolicyAuthority.AUDIT_READ),
                "issuer", "subject");
        assertThatThrownBy(() -> service(5).verify(ordinaryAuditReader, 1, 1, null, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void appendBeyondPinnedBoundaryIsReportedWithoutChangingVerifiedRange() {
        var withAppend = new java.util.ArrayList<>(chain);
        withAppend.add(AuditEventDocument.from("event-5", new AuditEvent(
                new AuditActor("actor", Set.of(), Set.of()), AuditAction.READ_AUDIT_EVENTS,
                AuditResourceType.AUDIT_EVENT, "audit", Instant.parse("2026-10-25T10:00:05Z"),
                "correlation-5", "request-5", AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null, null), chain.get(3).eventHash(), 5));
        stubStore(withAppend);
        when(events.findLatestByPartitionKey(PARTITION))
                .thenReturn(java.util.Optional.of(chain.get(3)), java.util.Optional.of(chain.get(3)),
                        java.util.Optional.of(withAppend.get(4)), java.util.Optional.of(withAppend.get(4)));
        when(events.countByPartitionKey(PARTITION)).thenReturn(4L, 5L);

        var result = service(5).verify(auditor(), 1, 3, 3L, chain.get(2).eventHash());

        assertThat(result.chainRangeIntegrity())
                .isEqualTo(AuditChainRangeVerificationService.Status.CHAIN_RANGE_VERIFIED);
        assertThat(result.concurrentAppendObserved()).isTrue();
        assertThat(result.boundaryPosition()).isEqualTo(3L);
    }

    private AuditChainRangeVerificationService service(int maxRange) {
        return new AuditChainRangeVerificationService(events, anchors, maxRange);
    }

    private void stubStore(List<AuditEventDocument> stored) {
        var head = stored.stream().max(java.util.Comparator.comparing(AuditEventDocument::chainPosition)).orElse(null);
        when(events.findLatestByPartitionKey(PARTITION)).thenReturn(java.util.Optional.ofNullable(head));
        when(events.countByPartitionKey(PARTITION)).thenReturn(head == null ? 0L : head.chainPosition());
        when(events.findByPartitionKeyAndChainPositionBetween(anyString(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> stored.stream()
                        .filter(e -> e.chainPosition() >= (long) call.getArgument(1)
                                && e.chainPosition() <= (long) call.getArgument(2))
                        .sorted(java.util.Comparator.comparing(AuditEventDocument::chainPosition))
                        .limit((int) call.getArgument(3)).toList());
        when(anchors.findByPartitionKeyAndChainPositionBetween(anyString(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> stored.stream()
                        .filter(e -> e.chainPosition() >= (long) call.getArgument(1)
                                && e.chainPosition() <= (long) call.getArgument(2))
                        .sorted(java.util.Comparator.comparing(AuditEventDocument::chainPosition))
                        .limit((int) call.getArgument(3))
                        .map(e -> AuditAnchorDocument.from("anchor-" + e.chainPosition(), e)).toList());
    }

    private static List<AuditEventDocument> chain() {
        var result = new java.util.ArrayList<AuditEventDocument>();
        String previous = null;
        for (long position = 1; position <= 4; position++) {
            var event = AuditEventDocument.from("event-" + position, new AuditEvent(
                    new AuditActor("actor", Set.of(), Set.of()), AuditAction.READ_AUDIT_EVENTS,
                    AuditResourceType.AUDIT_EVENT, "audit", Instant.parse("2026-10-25T10:00:00Z").plusSeconds(position),
                    "correlation-" + position, "request-" + position, AuditOutcome.SUCCESS,
                    AuditFailureCategory.NONE, null, null), previous, position);
            result.add(event);
            previous = event.eventHash();
        }
        return List.copyOf(result);
    }

    private static PolicyGovernanceActor auditor() {
        return new PolicyGovernanceActor("auditor", Set.of(ModelPolicyAuthority.AUDIT_READ,
                ModelPolicyAuthority.PRODUCTION), "issuer", "subject");
    }
}
