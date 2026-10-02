package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditActor;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditChainLockRepository;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEvent;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventPublisher;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditFailureCategory;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.PersistentAuditEventPublisher;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.OutboxPublicationConfirmationProvenance;
import com.frauddetection.alert.outbox.OutboxPublisherCoordinator;
import com.frauddetection.alert.outbox.OutboxRecordResponse;
import com.frauddetection.alert.outbox.OutboxRecoveryService;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.alert.regulated.mutation.outbox.TransactionalOutboxRecoveryStrategy;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.AnalystDecisionStatusMapper;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.alert.service.DecisionOutboxWriter;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIf("dockerAvailable")
@Tag("integration")
@Tag("invariant-proof")
@Tag("evidence-gated-finalize")
class EvidenceGatedFinalizeCoordinatorIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationCommandRepository commandRepository;
    private AlertRepository alertRepository;
    private TransactionalOutboxRecordRepository outboxRepository;
    private AuditEventRepository auditEventRepository;
    private LocalMongoAuditPublisher auditPublisher;
    private RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter;
    private MongoRegulatedMutationCoordinator coordinator;

    static boolean dockerAvailable() {
        return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_coord_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        commandRepository = repositoryFactory.getRepository(RegulatedMutationCommandRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        outboxRepository = repositoryFactory.getRepository(TransactionalOutboxRecordRepository.class);
        auditEventRepository = new AuditEventRepository(mongoTemplate);
        ensureAuditIndexes();
        auditPublisher = new LocalMongoAuditPublisher(mongoTemplate);
        localAuditPhaseWriter = new RegulatedMutationLocalAuditPhaseWriter(
                auditEventRepository,
                new AuditAnchorRepository(mongoTemplate),
                new AuditChainLockRepository(mongoTemplate)
        );

        RegulatedMutationTransactionRunner transactionRunner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(new MongoTransactionManager(databaseFactory))
        );
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        coordinator = coordinator(
                commandRepository,
                transactionRunner,
                metrics,
                localAuditPhaseWriter
        );
    }

    private void ensureAuditIndexes() {
        mongoTemplate.indexOps(AuditEventDocument.class)
                .ensureIndex(new Index()
                        .on("partition_key", Sort.Direction.ASC)
                        .on("chain_position", Sort.Direction.ASC)
                        .unique()
                        .sparse()
                        .named("audit_partition_chain_position_uidx_test"));
        mongoTemplate.indexOps(AuditEventDocument.class)
                .ensureIndex(new Index()
                        .on("request_id", Sort.Direction.ASC)
                        .unique()
                        .sparse()
                        .named("audit_request_id_uidx_test"));
        mongoTemplate.indexOps(com.frauddetection.alert.audit.AuditAnchorDocument.class)
                .ensureIndex(new Index()
                        .on("partition_key", Sort.Direction.ASC)
                        .on("chain_position", Sort.Direction.ASC)
                        .unique()
                        .sparse()
                        .named("audit_anchor_partition_chain_position_uidx_test"));
    }

    private MongoRegulatedMutationCoordinator coordinator(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationTransactionRunner transactionRunner,
            AlertServiceMetrics metrics,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter
    ) {
        return coordinator(
                commandRepository,
                transactionRunner,
                metrics,
                localAuditPhaseWriter,
                mongoTemplate
        );
    }

    private MongoRegulatedMutationCoordinator coordinator(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationTransactionRunner transactionRunner,
            AlertServiceMetrics metrics,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter,
            MongoTemplate transitionMongoTemplate
    ) {
        RegulatedMutationAuditPhaseService auditPhaseService = new RegulatedMutationAuditPhaseService(
                auditEventRepository,
                new AuditService(new CurrentAnalystUser(), List.of(auditPublisher))
        );
        EvidencePreconditionEvaluator evidencePreconditionEvaluator = new EvidencePreconditionEvaluator(
                transactionRunner,
                provider(outboxRepository),
                provider(alertRepository),
                List.of(
                        new SubmitDecisionRecoveryStrategy(alertRepository),
                        new TransactionalOutboxRecoveryStrategy(outboxRepository)
                ),
                true
        );
        EvidenceGatedFinalizeExecutor evidenceGatedFinalizeExecutor = new EvidenceGatedFinalizeExecutor(
                commandRepository,
                transitionMongoTemplate,
                auditPhaseService,
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                evidencePreconditionEvaluator,
                localAuditPhaseWriter,
                RegulatedMutationProofTestFixtures.accepted(),
                Duration.ofSeconds(30)
        );
        return new MongoRegulatedMutationCoordinator(
                commandRepository,
                new RegulatedMutationExecutorRegistry(List.of(evidenceGatedFinalizeExecutor)),
                new RegulatedMutationConflictPolicy()
        );
    }

    private MongoRegulatedMutationCoordinator coordinatorWithPersistentAudit(
            RegulatedMutationCommandRepository commandRepository,
            RegulatedMutationTransactionRunner transactionRunner,
            AlertServiceMetrics metrics,
            RegulatedMutationLocalAuditPhaseWriter localAuditPhaseWriter
    ) {
        PersistentAuditEventPublisher persistentPublisher = new PersistentAuditEventPublisher(
                auditEventRepository,
                new AuditAnchorRepository(mongoTemplate),
                new AuditChainLockRepository(mongoTemplate),
                metrics,
                EvidenceGatedFinalizeCoordinatorIntegrationTest.<ExternalAuditAnchorPublisher>provider(null),
                false,
                false
        );
        RegulatedMutationAuditPhaseService auditPhaseService = new RegulatedMutationAuditPhaseService(
                auditEventRepository,
                new AuditService(new CurrentAnalystUser(), List.of(persistentPublisher))
        );
        EvidencePreconditionEvaluator evidencePreconditionEvaluator = new EvidencePreconditionEvaluator(
                transactionRunner,
                provider(outboxRepository),
                provider(alertRepository),
                List.of(
                        new SubmitDecisionRecoveryStrategy(alertRepository),
                        new TransactionalOutboxRecoveryStrategy(outboxRepository)
                ),
                true
        );
        EvidenceGatedFinalizeExecutor evidenceGatedFinalizeExecutor = new EvidenceGatedFinalizeExecutor(
                commandRepository,
                mongoTemplate,
                auditPhaseService,
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                evidencePreconditionEvaluator,
                localAuditPhaseWriter,
                RegulatedMutationProofTestFixtures.accepted(),
                Duration.ofSeconds(30)
        );
        return new MongoRegulatedMutationCoordinator(
                commandRepository,
                new RegulatedMutationExecutorRegistry(List.of(evidenceGatedFinalizeExecutor)),
                new RegulatedMutationConflictPolicy()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void shouldFinalizeSubmitDecisionThroughRealMongoCoordinatorPath() {
        alertRepository.save(alert("alert-success"));
        AtomicInteger businessMutations = new AtomicInteger();
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository,
                        mock(TransactionalOutboxRuntimeReadiness.class))
        );

        RegulatedMutationResult<SubmitAnalystDecisionResponse> result = coordinator.commit(command(
                "idem-success",
                "alert-success",
                businessMutations,
                context -> handler.applyDecision(
                        "alert-success",
                        request(),
                        AlertStatus.RESOLVED,
                        "principal-7",
                        "idem-success",
                        "request-hash-success",
                        context.commandId(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                )
        ));

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-success").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-success").orElseThrow();

        assertThat(result.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.response().operationStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getResponseSnapshot()).isNotNull();
        assertThat(command.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
        assertThat(command.isSuccessAuditRecorded()).isTrue();
        assertThat(alert.getAnalystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
        assertThat(alert.getDecisionOperationStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
        assertThat(outboxRepository.findByMutationCommandId(command.getId())).isPresent();
        assertThat(businessMutations).hasValue(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
        assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.ATTEMPTED)).isEqualTo(1);
        assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isEqualTo(1);
        assertThat(auditPublisher.successPublishCalls).isZero();
    }

    @Test
    void shouldRollbackCoordinatorPathWhenOutboxWriteFailsInsideFinalize() {
        alertRepository.save(alert("alert-outbox-fail"));
        AtomicInteger businessMutations = new AtomicInteger();

        assertThatThrownBy(() -> coordinator.commit(command(
                "idem-outbox-fail",
                "alert-outbox-fail",
                businessMutations,
                context -> {
                    AlertDocument alert = alertRepository.findById("alert-outbox-fail").orElseThrow();
                    alert.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
                    alert.setDecidedAt(Instant.now());
                    alertRepository.save(alert);
                    throw new DataAccessResourceFailureException("outbox write failed");
                }
        ))).isInstanceOf(DataAccessResourceFailureException.class);

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-outbox-fail").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-outbox-fail").orElseThrow();

        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getLocalCommitMarker()).isNull();
        assertThat(command.getSuccessAuditId()).isNull();
        assertThat(command.isSuccessAuditRecorded()).isFalse();
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(outboxRepository.count()).isZero();
        assertThat(businessMutations).hasValue(1);

        RegulatedMutationResult<SubmitAnalystDecisionResponse> replay = coordinator.commit(command(
                "idem-outbox-fail",
                "alert-outbox-fail",
                businessMutations,
                context -> {
                    throw new AssertionError("business mutation must not rerun");
                }
        ));
        assertThat(replay.state()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(businessMutations).hasValue(1);
    }

    @Test
    void shouldRollbackCoordinatorPathWhenSuccessAuditPersistenceFailsInsideFinalize() {
        alertRepository.save(alert("alert-success-audit-fail"));
        coordinator = coordinator(
                commandRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                new FailingLocalAuditPhaseWriter()
        );
        AtomicInteger businessMutations = new AtomicInteger();
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository,
                        mock(TransactionalOutboxRuntimeReadiness.class))
        );

        assertThatThrownBy(() -> coordinator.commit(command(
                "idem-success-audit-fail",
                "alert-success-audit-fail",
                businessMutations,
                context -> handler.applyDecision(
                        "alert-success-audit-fail",
                        request(),
                        AlertStatus.RESOLVED,
                        "principal-7",
                        "idem-success-audit-fail",
                        "request-hash-success-audit-fail",
                        context.commandId(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                )
        ))).isInstanceOf(DataAccessResourceFailureException.class);

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-success-audit-fail").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-success-audit-fail").orElseThrow();

        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getLocalCommitMarker()).isNull();
        assertThat(command.isSuccessAuditRecorded()).isFalse();
        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(outboxRepository.count()).isZero();
        assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isZero();
        assertThat(auditPublisher.successPublishCalls).isZero();
    }

    @Test
    void shouldRollbackAssignedFinalizeFieldsWhenCommandSaveFailsBeforeTransactionCommit() {
        alertRepository.save(alert("alert-corruption-proof"));
        MongoTemplate throwingMongoTemplate = throwOnceOnFinalizedFencedTransition();
        coordinator = coordinator(
                commandRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                localAuditPhaseWriter,
                throwingMongoTemplate
        );
        AtomicInteger businessMutations = new AtomicInteger();
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository,
                        mock(TransactionalOutboxRuntimeReadiness.class))
        );

        assertThatThrownBy(() -> coordinator.commit(command(
                "idem-corruption-proof",
                "alert-corruption-proof",
                businessMutations,
                context -> handler.applyDecision(
                        "alert-corruption-proof",
                        request(),
                        AlertStatus.RESOLVED,
                        "principal-7",
                        "idem-corruption-proof",
                        "request-hash-corruption-proof",
                        context.commandId(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                )
        ))).isInstanceOf(DataAccessResourceFailureException.class);

        RegulatedMutationCommandDocument command = commandRepository.findByIdempotencyKey("idem-corruption-proof").orElseThrow();
        AlertDocument alert = alertRepository.findById("alert-corruption-proof").orElseThrow();

        assertThat(alert.getAnalystDecision()).isNull();
        assertThat(outboxRepository.count()).isZero();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getOutboxEventId()).isNull();
        assertThat(command.getLocalCommitMarker()).isNull();
        assertThat(command.getSuccessAuditId()).isNull();
        assertThat(command.isSuccessAuditRecorded()).isFalse();
        assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isZero();

        RegulatedMutationResult<SubmitAnalystDecisionResponse> replay = coordinator.commit(command(
                "idem-corruption-proof",
                "alert-corruption-proof",
                businessMutations,
                context -> {
                    throw new AssertionError("business mutation must not rerun after finalize rollback");
                }
        ));
        assertThat(replay.state()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(businessMutations).hasValue(1);
    }

    @Test
    void competingOutboxApprovalCommandsCommitExactlyOneDurableResolution() throws Exception {
        String eventId = "outbox-coordinator-race-event";
        String alertId = "outbox-coordinator-race-alert";
        alertRepository.save(alertProjection(alertId, eventId));
        outboxRepository.save(confirmationUnknownRecord(eventId, alertId));
        coordinator = coordinatorWithPersistentAudit(
                commandRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                localAuditPhaseWriter
        );
        OutboxRecoveryService service = outboxRecoveryService(coordinator);

        OutboxRecordResponse pending = service.resolveConfirmation(
                eventId,
                resolutionRequest(null, "request reason", "request-verifier", "offset=40"),
                "requester",
                "outbox-request-idempotency"
        );
        assertThat(pending.resolutionPending()).isTrue();
        assertThat(pending.resolutionRequestId()).isNotBlank();

        CountDownLatch start = new CountDownLatch(1);
        List<ApprovalAttempt> attempts;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ApprovalAttempt> first = executor.submit(() -> approveOutbox(
                    start,
                    service,
                    eventId,
                    pending.resolutionRequestId(),
                    "outbox-approval-a",
                    "approver-a",
                    "approval reason a",
                    "offset=41"
            ));
            Future<ApprovalAttempt> second = executor.submit(() -> approveOutbox(
                    start,
                    service,
                    eventId,
                    pending.resolutionRequestId(),
                    "outbox-approval-b",
                    "approver-b",
                    "approval reason b",
                    "offset=42"
            ));
            start.countDown();
            attempts = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        ApprovalAttempt winner = attempts.stream().filter(ApprovalAttempt::succeeded).findFirst().orElseThrow();
        ApprovalAttempt loser = attempts.stream().filter(attempt -> !attempt.succeeded()).findFirst().orElseThrow();
        assertThat(attempts).filteredOn(ApprovalAttempt::succeeded).hasSize(1);
        assertThat(loser.failure()).isNotNull();

        TransactionalOutboxRecordDocument source = outboxRepository.findById(eventId).orElseThrow();
        assertThat(source.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(source.getPublicationConfirmationProvenance())
                .isEqualTo(OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED);
        assertThat(source.getResolutionRequestId()).isEqualTo(pending.resolutionRequestId());
        assertThat(source.getResolutionProposedOutcome()).isEqualTo(OutboxConfirmationResolution.PUBLISHED.name());
        assertThat(source.getResolutionRequestedBy()).isEqualTo("requester");
        assertThat(source.getResolutionRequestReason()).isEqualTo("request reason");
        assertThat(source.getResolutionApprovedBy()).isEqualTo(winner.actorId());
        assertThat(source.getResolutionApprovalReason()).isEqualTo(winner.reason());

        RegulatedMutationCommandDocument winningCommand =
                commandRepository.findByIdempotencyKey(winner.idempotencyKey()).orElseThrow();
        RegulatedMutationCommandDocument losingCommand =
                commandRepository.findByIdempotencyKey(loser.idempotencyKey()).orElseThrow();
        assertThat(winningCommand.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(winningCommand.isSuccessAuditRecorded()).isTrue();
        assertThat(winningCommand.getResponseSnapshot()).isNotNull();
        assertThat(countAudit(winningCommand.getId(), RegulatedMutationAuditPhase.SUCCESS)).isEqualTo(1);
        assertThat(losingCommand.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(losingCommand.isSuccessAuditRecorded()).isFalse();
        assertThat(losingCommand.getResponseSnapshot()).isNull();
        assertThat(countAudit(losingCommand.getId(), RegulatedMutationAuditPhase.SUCCESS)).isZero();
        assertThat(countAudit(losingCommand.getId(), RegulatedMutationAuditPhase.FAILED)).isEqualTo(1);

        MongoRegulatedMutationCoordinator restartedCoordinator = coordinatorWithPersistentAudit(
                commandRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                localAuditPhaseWriter
        );
        OutboxRecordResponse replay = outboxRecoveryService(restartedCoordinator).resolveConfirmation(
                eventId,
                resolutionRequest(
                        pending.resolutionRequestId(),
                        winner.reason(),
                        winner.actorId(),
                        winner.evidenceReference()
                ),
                winner.actorId(),
                winner.idempotencyKey()
        );
        assertThat(replay.status()).isEqualTo(TransactionalOutboxStatus.PUBLISHED.name());
        assertThat(replay.resolutionRequestId()).isEqualTo(pending.resolutionRequestId());
        assertThat(replay.resolutionProposedOutcome()).isEqualTo(OutboxConfirmationResolution.PUBLISHED.name());

        TransactionalOutboxRecordDocument afterRestart = outboxRepository.findById(eventId).orElseThrow();
        AlertDocument projection = alertRepository.findById(alertId).orElseThrow();
        assertThat(afterRestart.getProjectionRevision()).isEqualTo(source.getProjectionRevision());
        assertThat(afterRestart.getResolutionApprovedBy()).isEqualTo(winner.actorId());
        assertThat(projection.getDecisionOutboxEventId()).isEqualTo(eventId);
        assertThat(projection.getDecisionOutboxProjectionRevision()).isEqualTo(afterRestart.getProjectionRevision());
        assertThat(projection.getDecisionOutboxResolutionRequestId()).isEqualTo(pending.resolutionRequestId());
        assertThat(projection.getDecisionOutboxResolutionProposedOutcome())
                .isEqualTo(OutboxConfirmationResolution.PUBLISHED.name());
        assertThat(projection.getDecisionOutboxResolutionRequestedBy()).isEqualTo("requester");
        assertThat(projection.getDecisionOutboxResolutionRequestReason()).isEqualTo("request reason");
        assertThat(projection.getDecisionOutboxResolutionApprovedBy()).isEqualTo(winner.actorId());
        assertThat(projection.getDecisionOutboxResolutionApprovalReason()).isEqualTo(winner.reason());
        assertThat(projection.getDecisionOutboxPublicationConfirmationProvenance())
                .isEqualTo(OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED.name());
    }

    @Test
    void shouldKeepAuditChainContinuousUnderConcurrentFinalizations() throws Exception {
        alertRepository.save(alert("alert-concurrent-a"));
        alertRepository.save(alert("alert-concurrent-b"));
        coordinator = coordinatorWithPersistentAudit(
                commandRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                localAuditPhaseWriter
        );
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository,
                        mock(TransactionalOutboxRuntimeReadiness.class))
        );
        AtomicInteger firstBusinessMutations = new AtomicInteger();
        AtomicInteger secondBusinessMutations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegulatedMutationResult<SubmitAnalystDecisionResponse>>> futures = new java.util.ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            futures.add(executor.submit(() -> concurrentCommit(start, "idem-concurrent-a", "alert-concurrent-a", firstBusinessMutations, handler)));
            futures.add(executor.submit(() -> concurrentCommit(start, "idem-concurrent-b", "alert-concurrent-b", secondBusinessMutations, handler)));
            start.countDown();

            List<Throwable> failures = new java.util.ArrayList<>();
            List<RegulatedMutationResult<SubmitAnalystDecisionResponse>> results = new java.util.ArrayList<>();
            for (Future<RegulatedMutationResult<SubmitAnalystDecisionResponse>> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }
            String failureDiagnostics = failureDiagnostics(failures);

            assertThat(results)
                    .as("Concurrent finalization failures:%n%s", failureDiagnostics)
                    .extracting(RegulatedMutationResult::state)
                    .containsOnly(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
            assertThat(results.size() + failures.size())
                    .as("Concurrent finalization failures:%n%s", failureDiagnostics)
                    .isEqualTo(2);
            assertThat(results)
                    .as("Concurrent finalization failures:%n%s", failureDiagnostics)
                    .isNotEmpty();
            assertThat(failures)
                    .as("Concurrent finalization failures:%n%s", failureDiagnostics)
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(failures)
                    .allSatisfy(failure -> assertThat(failure)
                            .isInstanceOfAny(RuntimeException.class));
        }

        List<RegulatedMutationCommandDocument> commands = List.of(
                commandRepository.findByIdempotencyKey("idem-concurrent-a").orElseThrow(),
                commandRepository.findByIdempotencyKey("idem-concurrent-b").orElseThrow()
        );

        for (RegulatedMutationCommandDocument command : commands) {
            assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.ATTEMPTED)).isEqualTo(1);
            assertThat(command.getState()).isNotEqualTo(RegulatedMutationState.FINALIZING);
            if (command.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL) {
                assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isEqualTo(1);
                assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.FAILED)).isZero();
                assertThat(command.getResponseSnapshot()).isNotNull();
                assertThat(command.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
                assertThat(command.isSuccessAuditRecorded()).isTrue();
                assertThat(command.getSuccessAuditId()).isNotBlank();
                assertThat(command.getOutboxEventId()).isNotBlank();
            } else {
                assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
                assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isZero();
                assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.FAILED)).isEqualTo(1);
                assertThat(command.getFailedAuditId()).isNotBlank();
                assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
            }
        }

        long finalizedCommands = commands.stream()
                .filter(command -> command.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL)
                .count();
        assertThat(firstBusinessMutations).hasValueLessThanOrEqualTo(1);
        assertThat(secondBusinessMutations).hasValueLessThanOrEqualTo(1);
        assertThat(firstBusinessMutations.get() + secondBusinessMutations.get())
                .isBetween((int) finalizedCommands, 2);
        List<AuditEventDocument> auditEvents = auditEventRepository.findFullChain("source_service:alert-service", 10);
        List<com.frauddetection.alert.audit.AuditAnchorDocument> anchors = mongoTemplate.find(
                new Query(),
                com.frauddetection.alert.audit.AuditAnchorDocument.class
        );

        assertThat(auditEvents).hasSize(2 + commands.size());
        assertContinuousChain(auditEvents);
        assertThat(auditEvents.stream().map(AuditEventDocument::auditId)).doesNotHaveDuplicates();
        assertThat(auditEvents.stream().map(AuditEventDocument::chainPosition)).doesNotHaveDuplicates();
        assertThat(auditEvents.stream().skip(1).map(AuditEventDocument::previousEventHash)).doesNotHaveDuplicates();
        assertThat(anchors).hasSize(auditEvents.size());
        assertThat(anchors.stream().map(com.frauddetection.alert.audit.AuditAnchorDocument::lastEventHash))
                .containsExactlyInAnyOrderElementsOf(auditEvents.stream().map(AuditEventDocument::eventHash).toList())
                .doesNotHaveDuplicates();
        assertThat(outboxRepository.count()).isEqualTo(finalizedCommands);
        assertThat(List.of(
                alertRepository.findById("alert-concurrent-a").orElseThrow(),
                alertRepository.findById("alert-concurrent-b").orElseThrow()
        ).stream().filter(alert -> alert.getAnalystDecision() == AnalystDecision.CONFIRMED_FRAUD).count())
                .isEqualTo(finalizedCommands);
        assertThat(commands).noneMatch(command -> command.getState() == RegulatedMutationState.FINALIZING);
    }

    private RegulatedMutationResult<SubmitAnalystDecisionResponse> concurrentCommit(
            CountDownLatch start,
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations,
            SubmitDecisionMutationHandler handler
    ) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return coordinator.commit(command(
                idempotencyKey,
                alertId,
                businessMutations,
                context -> handler.applyDecision(
                        alertId,
                        request(),
                        AlertStatus.RESOLVED,
                        "principal-7",
                        idempotencyKey,
                        "request-hash-" + idempotencyKey,
                        context.commandId(),
                        SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                )
        ));
    }

    private RegulatedMutationCommand<AlertDocument, SubmitAnalystDecisionResponse> command(
            String idempotencyKey,
            String alertId,
            AtomicInteger businessMutations,
            BusinessMutation<AlertDocument> mutation
    ) {
        return new RegulatedMutationCommand<>(
                idempotencyKey,
                "principal-7",
                alertId,
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-" + alertId,
                "request-hash-" + idempotencyKey,
                context -> {
                    businessMutations.incrementAndGet();
                    return mutation.execute(context);
                },
                this::response,
                RegulatedMutationResponseSnapshot::from,
                RegulatedMutationResponseSnapshot::toSubmitDecisionResponse,
                state -> new SubmitAnalystDecisionResponse(
                        alertId,
                        null,
                        AlertStatus.OPEN,
                        null,
                        null,
                        new RegulatedMutationPublicStatusMapper().submitDecisionStatus(
                                state,
                                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
                        )
                ),
                RegulatedMutationIntentHasher.submitDecision(
                        alertId,
                        "principal-7",
                        AnalystDecision.CONFIRMED_FRAUD,
                        "Confirmed after manual review",
                        List.of("chargeback")
                ),
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
        );
    }

    private SubmitAnalystDecisionResponse response(AlertDocument saved, RegulatedMutationState state) {
        return new SubmitAnalystDecisionResponse(
                saved.getAlertId(),
                saved.getAnalystDecision(),
                saved.getAlertStatus(),
                saved.getDecisionOutboxEvent() == null ? null : saved.getDecisionOutboxEvent().eventId(),
                saved.getDecidedAt(),
                new RegulatedMutationPublicStatusMapper().submitDecisionStatus(
                        state,
                        RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
                )
        );
    }

    private SubmitAnalystDecisionRequest request() {
        return new SubmitAnalystDecisionRequest(
                "principal-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback"),
                Map.of()
        );
    }

    private OutboxRecoveryService outboxRecoveryService(RegulatedMutationCoordinator mutationCoordinator) {
        return new OutboxRecoveryService(
                outboxRepository,
                mongoTemplate,
                mock(OutboxPublisherCoordinator.class),
                mutationCoordinator,
                new OutboxConfirmationResolutionMutationHandler(outboxRepository, mongoTemplate, true, true),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                Duration.ofMinutes(2)
        );
    }

    private ApprovalAttempt approveOutbox(
            CountDownLatch start,
            OutboxRecoveryService service,
            String eventId,
            String pendingRequestId,
            String idempotencyKey,
            String actorId,
            String reason,
            String evidenceReference
    ) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            OutboxRecordResponse response = service.resolveConfirmation(
                    eventId,
                    resolutionRequest(pendingRequestId, reason, actorId, evidenceReference),
                    actorId,
                    idempotencyKey
            );
            return new ApprovalAttempt(idempotencyKey, actorId, reason, evidenceReference, response, null);
        } catch (RuntimeException failure) {
            return new ApprovalAttempt(idempotencyKey, actorId, reason, evidenceReference, null, failure);
        }
    }

    private OutboxConfirmationResolutionRequest resolutionRequest(
            String pendingRequestId,
            String reason,
            String verifiedBy,
            String evidenceReference
    ) {
        Instant verifiedAt = pendingRequestId == null
                ? Instant.parse("2026-10-01T08:00:00Z")
                : Instant.parse("2026-10-01T09:00:00Z");
        return new OutboxConfirmationResolutionRequest(
                OutboxConfirmationResolution.PUBLISHED,
                pendingRequestId,
                reason,
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_OFFSET,
                        evidenceReference,
                        verifiedAt,
                        verifiedBy
                )
        );
    }

    private TransactionalOutboxRecordDocument confirmationUnknownRecord(String eventId, String alertId) {
        Instant now = Instant.parse("2026-10-01T07:00:00Z");
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setDedupeKey("dedupe-" + eventId);
        record.setMutationCommandId("source-command");
        record.setResourceType(AuditResourceType.ALERT.name());
        record.setResourceId(alertId);
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash("payload-hash");
        record.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        record.setAttempts(1);
        record.setProjectionRevision(0L);
        record.setConfirmationUnknownAt(now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private AlertDocument alertProjection(String alertId, String eventId) {
        AlertDocument document = alert(alertId);
        document.setDecisionOutboxEventId(eventId);
        document.setDecisionOutboxProjectionRevision(0L);
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        document.setDecisionOutboxAttempts(1);
        return document;
    }

    private AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-txn");
        document.setCustomerId(alertId + "-customer");
        document.setCorrelationId("corr-" + alertId);
        document.setCreatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        document.setAlertTimestamp(Instant.parse("2026-05-03T00:00:00Z"));
        document.setAlertStatus(AlertStatus.OPEN);
        document.setRiskLevel(RiskLevel.HIGH);
        document.setFraudScore(0.91d);
        document.setFeatureSnapshot(Map.of("velocity", 3));
        return document;
    }

    private record ApprovalAttempt(
            String idempotencyKey,
            String actorId,
            String reason,
            String evidenceReference,
            OutboxRecordResponse response,
            RuntimeException failure
    ) {
        boolean succeeded() {
            return response != null;
        }
    }

    private String failureDiagnostics(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return "none";
        }
        StringBuilder diagnostics = new StringBuilder();
        for (int index = 0; index < failures.size(); index++) {
            appendCauseChain(diagnostics, "future[" + index + "]", failures.get(index), 0);
        }
        return diagnostics.toString();
    }

    private void appendCauseChain(StringBuilder diagnostics, String label, Throwable failure, int depth) {
        if (failure == null) {
            diagnostics.append(label).append(": <null>\n");
            return;
        }
        diagnostics.append("  ".repeat(depth))
                .append(label)
                .append(": ")
                .append(failure.getClass().getName())
                .append(": ")
                .append(failure.getMessage())
                .append('\n');
        for (Throwable suppressed : failure.getSuppressed()) {
            appendCauseChain(diagnostics, "suppressed", suppressed, depth + 1);
        }
        if (failure.getCause() != null && failure.getCause() != failure) {
            appendCauseChain(diagnostics, "caused by", failure.getCause(), depth + 1);
        }
    }

    private long countAudit(String commandId, RegulatedMutationAuditPhase phase) {
        return mongoTemplate.count(
                Query.query(Criteria.where("request_id").is(commandId + ":" + phase.name())),
                AuditEventDocument.class
        );
    }

    private void assertContinuousChain(List<AuditEventDocument> documents) {
        AuditEventDocument previous = null;
        for (int index = 0; index < documents.size(); index++) {
            AuditEventDocument current = documents.get(index);
            assertThat(current.chainPosition()).isEqualTo(index + 1L);
            if (previous == null) {
                assertThat(current.previousEventHash()).isNull();
            } else {
                assertThat(current.previousEventHash()).isEqualTo(previous.eventHash());
            }
            previous = current;
        }
    }

    private MongoTemplate throwOnceOnFinalizedFencedTransition() {
        AtomicBoolean thrown = new AtomicBoolean();
        MongoTemplate spy = org.mockito.Mockito.spy(mongoTemplate);
        org.mockito.Mockito.doAnswer(invocation -> {
            Update update = invocation.getArgument(1);
            org.bson.Document set = (org.bson.Document) update.getUpdateObject().get("$set");
            if (set != null
                    && set.get("state") == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                    && thrown.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("command final fenced transition failed after field assignment");
            }
            return invocation.callRealMethod();
        }).when(spy).updateFirst(
                org.mockito.ArgumentMatchers.any(Query.class),
                org.mockito.ArgumentMatchers.any(Update.class),
                org.mockito.ArgumentMatchers.eq(RegulatedMutationCommandDocument.class)
        );
        return spy;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private final class LocalMongoAuditPublisher implements AuditEventPublisher {
        private final MongoTemplate mongoTemplate;
        private int successPublishCalls;

        private LocalMongoAuditPublisher(MongoTemplate mongoTemplate) {
            this.mongoTemplate = mongoTemplate;
        }

        @Override
        public void publish(AuditEvent event) {
            if (event.outcome() == AuditOutcome.SUCCESS) {
                successPublishCalls++;
            }
            long chainPosition = mongoTemplate.count(new Query(), AuditEventDocument.class) + 1L;
            AuditEventDocument document = new AuditEventDocument(
                    UUID.randomUUID().toString(),
                    event.action(),
                    event.actor() == null ? "principal-7" : event.actor().userId(),
                    event.actor() == null ? "principal-7" : event.actor().userId(),
                    List.of(),
                    "HUMAN",
                    List.of(),
                    event.action(),
                    event.resourceType(),
                    event.resourceId(),
                    event.timestamp(),
                    event.correlationId(),
                    event.requestId(),
                    "alert-service",
                    "source_service:alert-service",
                    chainPosition,
                    event.outcome(),
                    event.failureCategory() == null ? AuditFailureCategory.NONE : event.failureCategory(),
                    event.failureReason(),
                    event.metadataSummary(),
                    null,
                    "hash-" + chainPosition,
                    "SHA-256",
                    "1.0"
            );
            mongoTemplate.insert(document);
        }
    }

    private static final class FailingLocalAuditPhaseWriter extends RegulatedMutationLocalAuditPhaseWriter {
        private FailingLocalAuditPhaseWriter() {
            super(null, null, null);
        }

        @Override
        public <T> T withChainLock(java.util.function.Supplier<T> callback) {
            return callback.get();
        }

        @Override
        public String recordSuccessPhase(
                RegulatedMutationCommandDocument command,
                AuditAction action,
                AuditResourceType resourceType
        ) {
            throw new DataAccessResourceFailureException("success audit failed");
        }
    }
}
