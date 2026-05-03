package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class EvidencePreconditionEvaluator {

    public static final String TRANSACTION_CAPABILITY_UNAVAILABLE = "TRANSACTION_CAPABILITY_UNAVAILABLE";
    public static final String ATTEMPTED_AUDIT_UNAVAILABLE = "ATTEMPTED_AUDIT_UNAVAILABLE";
    public static final String OUTBOX_REPOSITORY_UNAVAILABLE = "OUTBOX_REPOSITORY_UNAVAILABLE";
    public static final String OUTBOX_RECOVERY_DISABLED = "OUTBOX_RECOVERY_DISABLED";
    public static final String RECOVERY_STRATEGY_UNAVAILABLE = "RECOVERY_STRATEGY_UNAVAILABLE";
    public static final String ACTOR_INTENT_MISMATCH = "ACTOR_INTENT_MISMATCH";
    public static final String RESOURCE_INTENT_MISMATCH = "RESOURCE_INTENT_MISMATCH";
    public static final String ACTION_INTENT_MISMATCH = "ACTION_INTENT_MISMATCH";
    public static final String BUSINESS_VALIDATION_FAILED = "BUSINESS_VALIDATION_FAILED";
    public static final String SUCCESS_AUDIT_KEY_UNAVAILABLE = "SUCCESS_AUDIT_KEY_UNAVAILABLE";

    private final RegulatedMutationTransactionRunner transactionRunner;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final AlertRepository alertRepository;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final boolean outboxRecoveryEnabled;
    private final boolean disabled;

    public EvidencePreconditionEvaluator(
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            ObjectProvider<AlertRepository> alertRepository,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            @Value("${app.outbox.recovery.enabled:true}") boolean outboxRecoveryEnabled
    ) {
        this.transactionRunner = transactionRunner;
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.alertRepository = alertRepository.getIfAvailable();
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.outboxRecoveryEnabled = outboxRecoveryEnabled;
        this.disabled = false;
    }

    EvidencePreconditionEvaluator() {
        this.transactionRunner = new RegulatedMutationTransactionRunner(RegulatedMutationTransactionMode.OFF, null);
        this.outboxRepository = null;
        this.alertRepository = null;
        this.recoveryStrategies = List.of();
        this.outboxRecoveryEnabled = false;
        this.disabled = true;
    }

    public <R, S> EvidencePreconditionResult evaluate(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document
    ) {
        List<String> checked = new ArrayList<>();
        List<String> skipped = new ArrayList<>(List.of(
                "EXTERNAL_ANCHOR_READINESS",
                "TRUST_AUTHORITY_SIGNING_READINESS",
                "EXTERNAL_WITNESS_POLICY_READINESS"
        ));
        if (disabled) {
            return EvidencePreconditionResult.satisfied(List.of("DISABLED_TEST_COMPATIBILITY"), skipped);
        }
        checked.add("MUTATION_MODEL_VERSION");
        if (document.mutationModelVersionOrLegacy() != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(
                    BUSINESS_VALIDATION_FAILED,
                    checked,
                    skipped
            );
        }
        checked.add("TRANSACTION_MODE_REQUIRED");
        if (transactionRunner.mode() != RegulatedMutationTransactionMode.REQUIRED) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(TRANSACTION_CAPABILITY_UNAVAILABLE, checked, skipped);
        }
        checked.add("ATTEMPTED_AUDIT_RECORDED");
        if (!document.isAttemptedAuditRecorded()) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(ATTEMPTED_AUDIT_UNAVAILABLE, checked, skipped);
        }
        checked.add("TRANSACTIONAL_OUTBOX_REPOSITORY_PRESENT");
        if (outboxRepository == null) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(OUTBOX_REPOSITORY_UNAVAILABLE, checked, skipped);
        }
        checked.add("OUTBOX_RECOVERY_ENABLED");
        if (!outboxRecoveryEnabled) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(OUTBOX_RECOVERY_DISABLED, checked, skipped);
        }
        checked.add("RECOVERY_STRATEGY_REGISTERED");
        if (recoveryStrategies.stream().noneMatch(strategy -> strategy.supports(command.action(), command.resourceType()))) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(RECOVERY_STRATEGY_UNAVAILABLE, checked, skipped);
        }
        checked.add("SUCCESS_AUDIT_PHASE_KEY_AVAILABLE");
        if (!StringUtils.hasText(document.getId()) && !StringUtils.hasText(document.getIdempotencyKey())) {
            return EvidencePreconditionResult.rejectedEvidenceUnavailable(SUCCESS_AUDIT_KEY_UNAVAILABLE, checked, skipped);
        }
        checked.add("INTENT_ACTOR_MATCH");
        if (document.getIntentActorId() != null && !Objects.equals(document.getIntentActorId(), command.actorId())) {
            return EvidencePreconditionResult.failedBusinessValidation(ACTOR_INTENT_MISMATCH, checked, skipped);
        }
        checked.add("INTENT_RESOURCE_MATCH");
        if (document.getIntentResourceId() != null && !Objects.equals(document.getIntentResourceId(), command.resourceId())) {
            return EvidencePreconditionResult.failedBusinessValidation(RESOURCE_INTENT_MISMATCH, checked, skipped);
        }
        checked.add("INTENT_ACTION_MATCH");
        if (document.getIntentAction() != null && !Objects.equals(document.getIntentAction(), command.action().name())) {
            return EvidencePreconditionResult.failedBusinessValidation(ACTION_INTENT_MISMATCH, checked, skipped);
        }
        checked.add("BUSINESS_VALIDATION");
        EvidencePreconditionResult businessValidation = validateSubmitDecisionBusinessState(command, document, checked, skipped);
        if (businessValidation != null) {
            return businessValidation;
        }
        return EvidencePreconditionResult.satisfied(checked, skipped);
    }

    private <R, S> EvidencePreconditionResult validateSubmitDecisionBusinessState(
            RegulatedMutationCommand<R, S> command,
            RegulatedMutationCommandDocument document,
            List<String> checked,
            List<String> skipped
    ) {
        if (command.action() != AuditAction.SUBMIT_ANALYST_DECISION || command.resourceType() != AuditResourceType.ALERT) {
            return null;
        }
        if (alertRepository == null) {
            return EvidencePreconditionResult.failedBusinessValidation(BUSINESS_VALIDATION_FAILED, checked, skipped);
        }
        if (!StringUtils.hasText(document.getIntentDecision())) {
            return EvidencePreconditionResult.failedBusinessValidation(BUSINESS_VALIDATION_FAILED, checked, skipped);
        }
        AlertDocument alert = alertRepository.findById(command.resourceId()).orElse(null);
        if (alert == null) {
            return EvidencePreconditionResult.failedBusinessValidation(BUSINESS_VALIDATION_FAILED, checked, skipped);
        }
        if (alert.getAnalystDecision() != null || alert.getDecidedAt() != null) {
            return EvidencePreconditionResult.failedBusinessValidation(BUSINESS_VALIDATION_FAILED, checked, skipped);
        }
        return null;
    }
}
