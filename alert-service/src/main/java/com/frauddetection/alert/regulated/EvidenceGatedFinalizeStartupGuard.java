package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditChainIndexInitializer;
import com.frauddetection.alert.audit.LocalAuditPhaseWriterProperties;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
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
    private final RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private final AuditChainIndexInitializer auditChainIndexInitializer;
    private final LocalAuditPhaseWriterProperties localAuditPhaseWriterProperties;
    private final List<RegulatedMutationRecoveryStrategy> recoveryStrategies;
    private final AlertServiceMetrics metrics;
    private final boolean transactionCapabilityProbeEnabled;
    private final boolean outboxRecoveryEnabled;

    public EvidenceGatedFinalizeStartupGuard(
            RegulatedMutationTransactionRunner transactionRunner,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<RegulatedMutationTransactionCapabilityProbe> transactionCapabilityProbe,
            ObjectProvider<TransactionalOutboxRecordRepository> outboxRepository,
            ObjectProvider<RegulatedMutationLocalAuditPhaseWriter> localAuditPhaseWriter,
            ObjectProvider<AuditChainIndexInitializer> auditChainIndexInitializer,
            LocalAuditPhaseWriterProperties localAuditPhaseWriterProperties,
            List<RegulatedMutationRecoveryStrategy> recoveryStrategies,
            AlertServiceMetrics metrics,
            @Value("${app.regulated-mutations.transaction-capability-probe.enabled:true}") boolean transactionCapabilityProbeEnabled,
            @Value("${app.outbox.recovery.enabled:true}") boolean outboxRecoveryEnabled
    ) {
        this.transactionRunner = transactionRunner;
        this.transactionManager = transactionManager.getIfAvailable();
        this.transactionCapabilityProbe = transactionCapabilityProbe.getIfAvailable();
        this.outboxRepository = outboxRepository.getIfAvailable();
        this.localAuditPhaseWriter = localAuditPhaseWriter.getIfAvailable();
        this.auditChainIndexInitializer = auditChainIndexInitializer.getIfAvailable();
        this.localAuditPhaseWriterProperties = localAuditPhaseWriterProperties == null
                ? new LocalAuditPhaseWriterProperties()
                : localAuditPhaseWriterProperties;
        this.recoveryStrategies = recoveryStrategies == null ? List.of() : List.copyOf(recoveryStrategies);
        this.metrics = metrics;
        this.transactionCapabilityProbeEnabled = transactionCapabilityProbeEnabled;
        this.outboxRecoveryEnabled = outboxRecoveryEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        RegulatedMutationDefinitions.all().forEach(definition ->
                metrics.recordEvidenceGatedFinalizeEnabled(definition.action().name(), true));
        require(
                transactionRunner.mode() == RegulatedMutationTransactionMode.REQUIRED,
                "app.regulated-mutations.transaction-mode",
                "Regulated mutation evidence-gated finalize requires transaction-mode=REQUIRED."
        );
        require(
                transactionManager != null,
                "mongo transaction manager",
                "Regulated mutation evidence-gated finalize requires a Mongo transaction manager."
        );
        require(
                transactionCapabilityProbeEnabled,
                "app.regulated-mutations.transaction-capability-probe.enabled",
                "Regulated mutation evidence-gated finalize requires transaction capability probe."
        );
        require(
                transactionCapabilityProbe != null,
                "transaction capability probe",
                "Regulated mutation evidence-gated finalize requires a transaction capability probe bean."
        );
        transactionCapabilityProbe.verify();
        require(
                outboxRepository != null,
                "TransactionalOutboxRecordRepository",
                "Regulated mutation evidence-gated finalize requires transactional outbox repository."
        );
        require(
                outboxRecoveryEnabled,
                "app.outbox.recovery.enabled",
                "Regulated mutation evidence-gated finalize requires outbox recovery."
        );
        require(
                localAuditPhaseWriter != null,
                "RegulatedMutationLocalAuditPhaseWriter",
                "Regulated mutation evidence-gated finalize requires a local audit phase writer."
        );
        require(
                localAuditPhaseWriterProperties.validForEvidenceGatedFinalize(),
                "app.audit.local-phase-writer",
                "Regulated mutation evidence-gated finalize requires finite local audit writer retry config."
        );
        require(
                auditChainIndexInitializer != null,
                "AuditChainIndexInitializer",
                "Regulated mutation evidence-gated finalize requires audit chain index initializer."
        );
        require(
                auditChainIndexInitializer.hasRequiredUniqueIndexes(),
                "audit chain unique indexes",
                "Regulated mutation evidence-gated finalize requires unique audit chain indexes."
        );
        for (RegulatedMutationDefinition definition : RegulatedMutationDefinitions.all()) {
            long matchingStrategies = recoveryStrategies.stream()
                    .filter(strategy -> strategy.supports(definition.action(), definition.resourceType()))
                    .count();
            require(
                    matchingStrategies == 1,
                    definition.action().name() + " recovery strategy",
                    "Canonical regulated mutation runtime requires exactly one recovery strategy per operation."
            );
        }
    }

    private void require(boolean valid, String setting, String reason) {
        if (!valid) {
            throw new IllegalStateException("Regulated mutation evidence-gated finalize startup guard failed: setting="
                    + setting + "; reason=" + reason);
        }
    }
}
