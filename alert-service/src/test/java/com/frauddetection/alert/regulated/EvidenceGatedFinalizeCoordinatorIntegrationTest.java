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
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.PersistentAuditEventPublisher;
import com.frauddetection.alert.audit.RegulatedMutationLocalAuditPhaseWriter;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.AnalystDecisionStatusMapper;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
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

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
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

    @BeforeEach
    void setUp() {
        String databaseName = "fdp29_coord_" + UUID.randomUUID().toString().replace("-", "");
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
        return new MongoRegulatedMutationCoordinator(
                commandRepository,
                mongoTemplate,
                new RegulatedMutationAuditPhaseService(
                        auditEventRepository,
                        new AuditService(new CurrentAnalystUser(), List.of(auditPublisher))
                ),
                mock(AuditDegradationService.class),
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                new EvidencePreconditionEvaluator(
                        transactionRunner,
                        provider(outboxRepository),
                        provider(alertRepository),
                        List.of(new SubmitDecisionRecoveryStrategy(alertRepository)),
                        true
                ),
                localAuditPhaseWriter,
                false,
                Duration.ofSeconds(30)
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
        return new MongoRegulatedMutationCoordinator(
                commandRepository,
                mongoTemplate,
                new RegulatedMutationAuditPhaseService(
                        auditEventRepository,
                        new AuditService(new CurrentAnalystUser(), List.of(persistentPublisher))
                ),
                mock(AuditDegradationService.class),
                metrics,
                transactionRunner,
                new RegulatedMutationPublicStatusMapper(),
                new EvidencePreconditionEvaluator(
                        transactionRunner,
                        provider(outboxRepository),
                        provider(alertRepository),
                        List.of(new SubmitDecisionRecoveryStrategy(alertRepository)),
                        true
                ),
                localAuditPhaseWriter,
                false,
                Duration.ofSeconds(30)
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
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository)
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
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository)
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
        RegulatedMutationCommandRepository throwingRepository = throwOnceAfterFinalizedSave(commandRepository);
        coordinator = coordinator(
                throwingRepository,
                new RegulatedMutationTransactionRunner(
                        RegulatedMutationTransactionMode.REQUIRED,
                        new TransactionTemplate(new MongoTransactionManager(databaseFactory))
                ),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                localAuditPhaseWriter
        );
        AtomicInteger businessMutations = new AtomicInteger();
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository)
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
    void shouldKeepAuditChainContinuousUnderConcurrentFdp29Finalizations() throws Exception {
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
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository)
        );
        AtomicInteger businessMutations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegulatedMutationResult<SubmitAnalystDecisionResponse>>> futures = new java.util.ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            futures.add(executor.submit(() -> concurrentCommit(start, "idem-concurrent-a", "alert-concurrent-a", businessMutations, handler)));
            futures.add(executor.submit(() -> concurrentCommit(start, "idem-concurrent-b", "alert-concurrent-b", businessMutations, handler)));
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

            assertThat(results)
                    .extracting(RegulatedMutationResult::state)
                    .containsOnly(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
            assertThat(results.size() + failures.size()).isEqualTo(2);
            assertThat(results).isNotEmpty();
            assertThat(failures).hasSizeLessThanOrEqualTo(1);
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
                assertThat(command.getResponseSnapshot()).isNotNull();
                assertThat(command.getLocalCommitMarker()).isEqualTo("EVIDENCE_GATED_FINALIZED");
                assertThat(command.isSuccessAuditRecorded()).isTrue();
                assertThat(command.getSuccessAuditId()).isNotBlank();
                assertThat(command.getOutboxEventId()).isNotBlank();
            } else {
                assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
                assertThat(countAudit(command.getId(), RegulatedMutationAuditPhase.SUCCESS)).isZero();
                assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
            }
        }

        long finalizedCommands = commands.stream()
                .filter(command -> command.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL)
                .count();
        List<AuditEventDocument> auditEvents = auditEventRepository.findFullChain("source_service:alert-service", 10);
        List<com.frauddetection.alert.audit.AuditAnchorDocument> anchors = mongoTemplate.find(
                new Query(),
                com.frauddetection.alert.audit.AuditAnchorDocument.class
        );

        assertThat(auditEvents).hasSize((int) (2 + finalizedCommands));
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

    private RegulatedMutationCommandRepository throwOnceAfterFinalizedSave(
            RegulatedMutationCommandRepository delegate
    ) {
        AtomicBoolean thrown = new AtomicBoolean();
        return (RegulatedMutationCommandRepository) Proxy.newProxyInstance(
                RegulatedMutationCommandRepository.class.getClassLoader(),
                new Class<?>[]{RegulatedMutationCommandRepository.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if ("save".equals(method.getName())
                                && args != null
                                && args.length == 1
                                && args[0] instanceof RegulatedMutationCommandDocument document
                                && document.getState() == RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
                                && thrown.compareAndSet(false, true)) {
                            throw new DataAccessResourceFailureException("command final save failed after field assignment");
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
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
        public String recordSuccessPhase(
                RegulatedMutationCommandDocument command,
                AuditAction action,
                AuditResourceType resourceType
        ) {
            throw new DataAccessResourceFailureException("success audit failed");
        }
    }
}
