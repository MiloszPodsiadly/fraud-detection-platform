package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Component
public class EvidenceGatedFinalizeStartupGuard implements ApplicationRunner {

    private final RegulatedMutationTransactionRunner transactionRunner;
    private final PlatformTransactionManager transactionManager;
    private final RegulatedMutationTransactionCapabilityProbe transactionCapabilityProbe;
    private final TransactionalOutboxRecordRepository outboxRepository;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final AlertServiceMetrics metrics;
    private final boolean globalEnabled;
    private final boolean submitDecisionEnabled;
    private final boolean fraudCaseUpdateEnabled;
    private final boolean trustIncidentEnabled;
    private final boolean outboxResolutionEnabled;
    private final boolean transactionCapabilityProbeEnabled;
    private final boolean outboxRecoveryEnabled;

    public EvidenceGatedFinalizeStartupGuard(
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<RegulatedMutationTransactionCapabilityProbe> transactionCapabilityProbe,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            AlertServiceMetrics metrics,
            @Value("${app.regulated-mutations.evidence-gated-finalize.enabled:false}") boolean globalEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled:false}") boolean submitDecisionEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.fraud-case-update.enabled:false}") boolean fraudCaseUpdateEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.trust-incident.enabled:false}") boolean trustIncidentEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.outbox-resolution.enabled:false}") boolean outboxResolutionEnabled,
            @Value("${app.regulated-mutations.transaction-capability-probe.enabled:true}") boolean transactionCapabilityProbeEnabled,
            @Value("${app.outbox.recovery.enabled:true}") boolean outboxRecoveryEnabled
    ) {
        this.transactionRunner = transactionRunner;
        this.transactionManager = transactionManager.getIfAvailable();
        this.transactionCapabilityProbe = transactionCapabilityProbe.getIfAvailable();
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.metrics = metrics;
        this.globalEnabled = globalEnabled;
        this.submitDecisionEnabled = submitDecisionEnabled;
        this.fraudCaseUpdateEnabled = fraudCaseUpdateEnabled;
        this.trustIncidentEnabled = trustIncidentEnabled;
        this.outboxResolutionEnabled = outboxResolutionEnabled;
        this.transactionCapabilityProbeEnabled = transactionCapabilityProbeEnabled;
        this.outboxRecoveryEnabled = outboxRecoveryEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean active = globalEnabled && anyMutationEnabled();
        metrics.recordEvidenceGatedFinalizeEnabled("SUBMIT_ANALYST_DECISION", globalEnabled && submitDecisionEnabled);
        if (!active) {
            return;
        }
        require(
                !fraudCaseUpdateEnabled && !trustIncidentEnabled && !outboxResolutionEnabled,
                "unsupported-mutation",
                "FDP-29 evidence-gated finalize is implemented only for submit-decision."
        );
        require(
                transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED,
                "app.regulated-mutations.transaction-mode",
                "FDP-29 evidence-gated finalize requires transaction-mode=REQUIRED."
        );
        require(
                transactionManager != null,
                "mongo transaction manager",
                "FDP-29 evidence-gated finalize requires a Mongo transaction manager."
        );
        require(
                transactionCapabilityProbeEnabled,
                "app.regulated-mutations.transaction-capability-probe.enabled",
                "FDP-29 evidence-gated finalize requires transaction capability probe."
        );
        require(
                transactionCapabilityProbe != null,
                "transaction capability probe",
                "FDP-29 evidence-gated finalize requires a transaction capability probe bean."
        );
        transactionCapabilityProbe.verify();
        require(
                outboxRepository != null,
                "TransactionalOutboxRecordRepository",
                "FDP-29 evidence-gated finalize requires transactional outbox repository."
        );
        require(
                outboxRecoveryEnabled,
                "app.outbox.recovery.enabled",
                "FDP-29 evidence-gated finalize requires outbox recovery."
        );
        if (submitDecisionEnabled) {
            require(
                    recoveryStrategies.stream().anyMatch(strategy ->
                            strategy.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT)),
                    "submit-decision recovery strategy",
                    "FDP-29 evidence-gated finalize requires submit-decision recovery strategy."
            );
        }
    }

    private boolean anyMutationEnabled() {
        return submitDecisionEnabled || fraudCaseUpdateEnabled || trustIncidentEnabled || outboxResolutionEnabled;
    }

    private void require(boolean valid, String setting, String reason) {
        if (!valid) {
            throw new IllegalStateException("FDP-29 evidence-gated finalize startup guard failed: setting="
                    + setting + "; reason=" + reason);
        }
    }
}
