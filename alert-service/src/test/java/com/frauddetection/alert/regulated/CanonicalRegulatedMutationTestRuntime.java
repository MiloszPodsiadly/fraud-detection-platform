package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

public final class CanonicalRegulatedMutationTestRuntime {

    private CanonicalRegulatedMutationTestRuntime() {
    }

    public static RegulatedMutationCoordinator coordinator(
            RegulatedMutationCommandRepository commandRepository,
            MongoTemplate mongoTemplate,
            RegulatedMutationAuditPhaseService auditPhaseService,
            AlertServiceMetrics metrics
    ) {
        RegulatedMutationTransactionRunner transactionRunner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(new NoopTransactionManager())
        );
        RegulatedMutationLocalAuditPhaseWriter localAuditWriter = new RegulatedMutationLocalAuditPhaseWriter(
                null,
                null,
                null
        ) {
            @Override
            public String recordSuccessPhase(
                    RegulatedMutationCommandDocument command,
                    com.frauddetection.alert.audit.AuditAction action,
                    com.frauddetection.alert.audit.AuditResourceType resourceType
            ) {
                return auditPhaseService.recordPhase(command, action, resourceType, AuditOutcome.SUCCESS, null);
            }
        };
        EvidenceGatedFinalizeExecutor executor = new EvidenceGatedFinalizeExecutor(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                new EvidencePreconditionEvaluator(),
                localAuditWriter,
                Duration.ofSeconds(30)
        );
        return new MongoRegulatedMutationCoordinator(
                commandRepository,
                new RegulatedMutationExecutorRegistry(List.of(executor))
        );
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
