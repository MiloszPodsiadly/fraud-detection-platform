package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatedMutationConflictPolicyTest {

    private final RegulatedMutationConflictPolicy policy = new RegulatedMutationConflictPolicy();

    @Test
    void sameHashAndSameActorReturnsExisting() {
        RegulatedMutationCommandDocument existing = document("request-hash-1", "principal-7");
        RegulatedMutationCommand<String, String> command = command("request-hash-1", "principal-7");

        assertThat(policy.existingOrConflict(existing, command)).isSameAs(existing);
    }

    @Test
    void differentRequestHashThrowsConflict() {
        RegulatedMutationCommandDocument existing = document("different-request-hash", "principal-7");

        assertThatThrownBy(() -> policy.existingOrConflict(existing, command("request-hash-1", "principal-7")))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void differentActorThrowsConflict() {
        RegulatedMutationCommandDocument existing = document("request-hash-1", "different-actor");

        assertThatThrownBy(() -> policy.existingOrConflict(existing, command("request-hash-1", "principal-7")))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void nullIntentActorFallsBackToCurrentCommandActor() {
        RegulatedMutationCommandDocument existing = document("request-hash-1", null);

        assertThat(policy.existingOrConflict(existing, command("request-hash-1", "principal-7"))).isSameAs(existing);
    }

    @Test
    void sameRequestHashDoesNotCompareFieldsOutsideTheCanonicalClaim() {
        RegulatedMutationCommandDocument existing = document("request-hash-1", "principal-7");
        existing.setIntentDecision("legacy-decision-value");

        assertThat(policy.existingOrConflict(existing, command("request-hash-1", "principal-7"))).isSameAs(existing);
    }

    @Test
    void nullInputsAreRejectedBeforeAnySideEffectCouldExist() {
        assertThatThrownBy(() -> policy.existingOrConflict(null, command("request-hash-1", "principal-7")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.existingOrConflict(document("request-hash-1", "principal-7"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RegulatedMutationCommandDocument document(String requestHash, String intentActorId) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setRequestHash(requestHash);
        document.setIntentActorId(intentActorId);
        document.setResourceId("alert-1");
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setRevision(0L);
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
                response -> null,
                snapshot -> "ok",
                state -> state.name(),
                null,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }
}
