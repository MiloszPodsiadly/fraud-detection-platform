package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatedMutationIdempotencyPrimitiveCompatibilityTest {

    private final RegulatedMutationConflictPolicy conflictPolicy = new RegulatedMutationConflictPolicy();

    @Test
    void genericHashRemainsDeterministic() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", List.of("two", "three"));
        first.put("a", "one");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", "one");
        second.put("b", List.of("two", "three"));

        assertThat(RegulatedMutationIntentHasher.hash(first))
                .isEqualTo(RegulatedMutationIntentHasher.hash(second))
                .isEqualTo("06461e5e47e4d2064b523d61e945529d7cbe9fc903673fdb7c90407f55d9a1c6");
    }

    @Test
    void canonicalValueFormatStaysCompatible() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("z", null);
        value.put("a", List.of("one", "two"));

        assertThat(RegulatedMutationIntentHasher.canonicalValue(value))
                .isEqualTo("{a:[one,two],z:null}");
        assertThat(RegulatedMutationIntentHasher.canonicalValue(Arrays.asList("one", null, "two")))
                .isEqualTo("[one,null,two]");
        assertThat(RegulatedMutationIntentHasher.canonicalValue(null)).isEqualTo("null");
        assertThat(RegulatedMutationIntentHasher.canonicalValue("plain")).isEqualTo("plain");
    }

    @Test
    void submitDecisionIntentHashRemainsStableForFixedFixture() {
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.submitDecision(
                "alert-1",
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                "Fraud confirmed",
                List.of("tag-a", "tag-b")
        );

        assertThat(intent.intentHash()).isEqualTo("5538b6899ae93f5e69188b1231ed7229c3f20611b553ce23cf181730c58bdafb");
        assertThat(intent.decision()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(intent.reasonHash()).isEqualTo("e25cc898c1780186ffe2b3347cdf4e795838221a47848f09c1853f221e721bd5");
        assertThat(intent.tagsHash()).isEqualTo("c765849a483e303dad99cfe03bf17300173cec53d6d2bb24b531b4ddeb58ed1d");
    }

    @Test
    void fraudCaseUpdateIntentHashRemainsStableForFixedFixture() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorId", "analyst-1");
        payload.put("caseId", "case-1");
        payload.put("status", AlertStatus.RESOLVED);

        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.fraudCaseUpdate(
                "case-1",
                "analyst-1",
                AlertStatus.RESOLVED,
                "investigator-1",
                "reviewed",
                List.of("tag-a", "tag-b"),
                payload
        );

        assertThat(intent.intentHash()).isEqualTo("20146684997d7f69e44706bed55720c7652e3ec166b074a4242091124dc814c5");
        assertThat(intent.status()).isEqualTo("RESOLVED");
        assertThat(intent.assigneeHash()).isEqualTo("4035bd515959befb01ee0386b5d9a6bcc2f7b60340f4a08d0897158a52c01d59");
        assertThat(intent.notesHash()).isEqualTo("e4f934f321eb76c9bf8b5103e0a0d9afe72d6e62ace3d3ea849790619bf7487a");
        assertThat(intent.payloadHash()).isEqualTo("13b3d35fd43721e49ad9840e47430d11c421a5adcc981c38cd58108caf18c268");
    }

    @Test
    void regulatedConflictPolicyKeepsSameClaimSemantics() {
        RegulatedMutationCommandDocument existing = existing("request-hash-1", "actor-1");

        assertThat(conflictPolicy.existingOrConflict(existing, command("request-hash-1", "actor-1")))
                .isSameAs(existing);
        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(existing, command("request-hash-2", "actor-1")))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);
        assertThatThrownBy(() -> conflictPolicy.existingOrConflict(existing, command("request-hash-1", "actor-2")))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    private RegulatedMutationCommandDocument existing(String requestHash, String actorId) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("mutation-1");
        document.setIdempotencyKey("idem-1");
        document.setRequestHash(requestHash);
        document.setIntentActorId(actorId);
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setState(RegulatedMutationState.REQUESTED);
        return document;
    }

    private RegulatedMutationCommand<String, String> command(String requestHash, String actorId) {
        return new RegulatedMutationCommand<>(
                "idem-1",
                actorId,
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                requestHash,
                context -> "ok",
                (result, state) -> state.name(),
                response -> new RegulatedMutationResponseSnapshot(
                        "alert-1",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-1",
                        java.time.Instant.parse("2026-05-10T10:00:00Z"),
                        com.frauddetection.alert.api.SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
                ),
                snapshot -> snapshot.operationStatus().name(),
                state -> state.name()
        );
    }
}
