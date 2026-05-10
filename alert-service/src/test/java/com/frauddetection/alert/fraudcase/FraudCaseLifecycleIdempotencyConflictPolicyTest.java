package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCaseLifecycleIdempotencyConflictPolicyTest {

    private final FraudCaseLifecycleIdempotencyConflictPolicy policy =
            new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy());

    @Test
    void shouldAllowSameActionActorScopeAndRequestHash() {
        FraudCaseLifecycleIdempotencyCommand command = command("hash-1", "actor-1", "ADD_NOTE", "case-1");

        assertThatCode(() -> policy.validateSameOperation(record("hash-1", "actor-1", "ADD_NOTE", "case-1"), command))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDifferentRequestActorActionOrScope() {
        FraudCaseLifecycleIdempotencyCommand command = command("hash-1", "actor-1", "ADD_NOTE", "case-1");

        assertThatThrownBy(() -> policy.validateSameOperation(record("hash-2", "actor-1", "ADD_NOTE", "case-1"), command))
                .isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThatThrownBy(() -> policy.validateSameOperation(record("hash-1", "actor-2", "ADD_NOTE", "case-1"), command))
                .isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThatThrownBy(() -> policy.validateSameOperation(record("hash-1", "actor-1", "CLOSE", "case-1"), command))
                .isInstanceOf(FraudCaseIdempotencyConflictException.class);
        assertThatThrownBy(() -> policy.validateSameOperation(record("hash-1", "actor-1", "ADD_NOTE", "case-2"), command))
                .isInstanceOf(FraudCaseIdempotencyConflictException.class);
    }

    private FraudCaseLifecycleIdempotencyCommand command(String requestHash, String actorId, String action, String scope) {
        return new FraudCaseLifecycleIdempotencyCommand("key", action, actorId, scope, requestHash, Instant.parse("2026-05-10T10:00:00Z"));
    }

    private FraudCaseLifecycleIdempotencyRecordDocument record(String requestHash, String actorId, String action, String scope) {
        FraudCaseLifecycleIdempotencyRecordDocument document = new FraudCaseLifecycleIdempotencyRecordDocument();
        document.setRequestHash(requestHash);
        document.setActorId(actorId);
        document.setAction(action);
        document.setCaseIdScope(scope);
        return document;
    }
}
