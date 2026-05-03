package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class EvidencePreconditionEvaluator {

    private static final String TRANSACTION_CAPABILITY_UNAVAILABLE = "TRANSACTION_CAPABILITY_UNAVAILABLE";
    private static final String OUTBOX_REPOSITORY_UNAVAILABLE = "OUTBOX_REPOSITORY_UNAVAILABLE";
    private static final String RECOVERY_STRATEGY_UNAVAILABLE = "RECOVERY_STRATEGY_UNAVAILABLE";
    private static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";
    private static final String ACTOR_INTENT_MISMATCH = "ACTOR_INTENT_MISMATCH";

    private final RegulatedMutationTransactionRunner transactionRunner;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final boolean outboxRecoveryEnabled;
    private final boolean disabled;

    public EvidencePreconditionEvaluator(
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            @Value("${app.outbox.recovery.enabled:true}") boolean outboxRecoveryEnabled
    ) {
        this.transactionRunner = transactionRunner;
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.outboxRecoveryEnabled = outboxRecoveryEnabled;
        this.disabled = false;
    }

    EvidencePreconditionEvaluator() {
        this.transactionRunner = new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null);
        this.outboxRepository = null;
        this.recoveryStrategies = List.of();
        this.outboxRecoveryEnabled = false;
        this.disabled = true;
    }

    public <R, S> EvidencePreconditionResult evaluate(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        if (disabled) {
            return EvidencePreconditionResult.passed();
        }
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(TRANSACTION_CAPABILITY_UNAVAILABLE);
        }
        if (!document.isAttemptedAuditRecorded()) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(ATTEMPTED_AUDIT_UNAVAILABLE);
        }
        if (outboxRepository == null || !outboxRecoveryEnabled) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(OUTBOX_REPOSITORY_UNAVAILABLE);
        }
        if (recoveryStrategies.stream().noneMatch(strategy -> strategy.supports(command.action(), command.resourceType()))) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(RECOVERY_STRATEGY_UNAVAILABLE);
        }
        if (document.getIntentActorId() != null && !Objects.equals(document.getIntentActorId(), command.actorId())) {
            return EvidencePreconditionResult.failedBusinessValidation(ACTOR_INTENT_MISMATCH);
        }
        if (document.getIntentResourceId() != null && !Objects.equals(document.getIntentResourceId(), command.resourceId())) {
            return EvidencePreconditionResult.failedBusinessValidation("RESOURCE_INTENT_MISMATCH");
        }
        if (document.getIntentAction() != null && !Objects.equals(document.getIntentAction(), command.action().name())) {
            return EvidencePreconditionResult.failedBusinessValidation("ACTION_INTENT_MISMATCH");
        }
        return EvidencePreconditionResult.passed();
    }
}
