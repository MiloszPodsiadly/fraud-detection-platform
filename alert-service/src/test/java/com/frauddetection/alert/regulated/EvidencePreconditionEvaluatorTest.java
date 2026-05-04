package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.enums.AlertStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidencePreconditionEvaluatorTest {

    @Test
    void shouldSatisfyLocalEvidenceGateWhenAllV1PreconditionsPass() {
        Fixture fixture = new Fixture();

        EvidencePreconditionResult result = fixture.evaluate(command(), document());

        assertThat(result.gateVersion()).isEqualTo(EvidencePreconditionGateVersion.LOCAL_EVIDENCE_GATE_V1);
        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.SATISFIED);
        assertThat(result.checkedPreconditions()).contains(
                "TRANSACTION_MODE_REQUIRED",
                "ATTEMPTED_AUDIT_RECORDED",
                "TRANSACTIONAL_OUTBOX_REPOSITORY_PRESENT",
                "OUTBOX_RECOVERY_ENABLED",
                "RECOVERY_STRATEGY_REGISTERED",
                "SUCCESS_AUDIT_PHASE_KEY_AVAILABLE",
                "BUSINESS_VALIDATION"
        );
        assertThat(result.skippedPreconditions()).contains("EXTERNAL_ANCHOR_READINESS", "TRUST_AUTHORITY_SIGNING_READINESS");
    }

    @Test
    void shouldRejectWhenAttemptedAuditIsMissing() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = document();
        document.setAttemptedAuditRecorded(false);

        EvidencePreconditionResult result = fixture.evaluate(command(), document);

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.REJECTED_EVIDENCE_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.ATTEMPTED_AUDIT_UNAVAILABLE);
    }

    @Test
    void shouldRejectWhenRecoveryStrategyIsMissing() {
        Fixture fixture = new Fixture(false, true, true, true);

        EvidencePreconditionResult result = fixture.evaluate(command(), document());

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.REJECTED_EVIDENCE_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.RECOVERY_STRATEGY_UNAVAILABLE);
    }

    @Test
    void shouldRejectWhenOutboxRecoveryIsDisabled() {
        Fixture fixture = new Fixture(true, true, true, false);

        EvidencePreconditionResult result = fixture.evaluate(command(), document());

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.REJECTED_EVIDENCE_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.OUTBOX_RECOVERY_DISABLED);
    }

    @Test
    void shouldRejectActorMismatchAsBusinessValidationFailure() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = document();
        document.setIntentActorId("other-actor");

        EvidencePreconditionResult result = fixture.evaluate(command(), document);

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.ACTOR_INTENT_MISMATCH);
    }

    @Test
    void shouldRejectResourceMismatchAsBusinessValidationFailure() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = document();
        document.setIntentResourceId("other-alert");

        EvidencePreconditionResult result = fixture.evaluate(command(), document);

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.RESOURCE_INTENT_MISMATCH);
    }

    @Test
    void shouldRejectActionMismatchAsBusinessValidationFailure() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument document = document();
        document.setIntentAction(AuditAction.UPDATE_FRAUD_CASE.name());

        EvidencePreconditionResult result = fixture.evaluate(command(), document);

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.ACTION_INTENT_MISMATCH);
    }

    @Test
    void shouldRejectAlreadyDecidedAlertBeforeVisibleMutation() {
        Fixture fixture = new Fixture();
        fixture.alert.setDecidedAt(Instant.parse("2026-05-03T00:00:00Z"));

        EvidencePreconditionResult result = fixture.evaluate(command(), document());

        assertThat(result.status()).isEqualTo(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION);
        assertThat(result.reasonCode()).isEqualTo(EvidencePreconditionEvaluator.BUSINESS_VALIDATION_FAILED);
    }

    private RegulatedMutationCommand<String, String> command() {
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-1",
                context -> "ok",
                (result, state) -> state.name(),
                response -> null,
                snapshot -> "ok",
                state -> state.name(),
                intent(),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private RegulatedMutationIntent intent() {
        return new RegulatedMutationIntent(
                "intent-hash",
                "alert-1",
                AuditAction.SUBMIT_ANALYST_DECISION.name(),
                "principal-7",
                "CONFIRMED_FRAUD",
                "reason-hash",
                "tags-hash",
                null,
                null,
                null,
                null
        );
    }

    private RegulatedMutationCommandDocument document() {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setIdempotencyKey("idem-1");
        document.setResourceId("alert-1");
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setAttemptedAuditRecorded(true);
        document.setIntentActorId("principal-7");
        document.setIntentResourceId("alert-1");
        document.setIntentAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setIntentDecision("CONFIRMED_FRAUD");
        return document;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private final class Fixture {
        private final AlertDocument alert = new AlertDocument();
        private final AlertRepository alertRepository = mock(AlertRepository.class);
        private final EvidencePreconditionEvaluator evaluator;

        private Fixture() {
            this(true, true, true, true);
        }

        private Fixture(
                boolean recoveryStrategyPresent,
                boolean outboxRepositoryPresent,
                boolean alertRepositoryPresent,
                boolean outboxRecoveryEnabled
        ) {
            alert.setAlertId("alert-1");
            alert.setAlertStatus(AlertStatus.OPEN);
            when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
            RegulatedMutationRecoveryStrategy strategy = mock(RegulatedMutationRecoveryStrategy.class);
            when(strategy.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT)).thenReturn(true);
            evaluator = new EvidencePreconditionEvaluator(
                    new RegulatedMutationTransactionRunner(
                            RegulatedMutationTransactionMode.REQUIRED,
                            new TransactionTemplate(mock(PlatformTransactionManager.class))
                    ),
                    provider(outboxRepositoryPresent ? mock(TransactionalOutboxRecordRepository.class) : null),
                    provider(alertRepositoryPresent ? alertRepository : null),
                    recoveryStrategyPresent ? List.of(strategy) : List.of(),
                    outboxRecoveryEnabled
            );
        }

        private EvidencePreconditionResult evaluate(
                RegulatedMutationCommand<String, String> command,
                RegulatedMutationCommandDocument document
        ) {
            return evaluator.evaluate(command, document);
        }
    }
}
