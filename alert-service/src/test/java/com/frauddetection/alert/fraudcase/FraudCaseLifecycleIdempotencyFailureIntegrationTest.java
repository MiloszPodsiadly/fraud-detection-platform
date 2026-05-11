package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.persistence.FraudCaseNoteRepository;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.alert.regulated.mutation.fraudcase.FraudCaseUpdateMutationHandler;
import com.frauddetection.alert.security.principal.AnalystActorResolver;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import com.frauddetection.alert.service.FraudCaseLifecycleService;
import com.frauddetection.alert.service.FraudCaseManagementService;
import com.frauddetection.alert.service.FraudCaseQueryService;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class FraudCaseLifecycleIdempotencyFailureIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private FraudCaseRepository caseRepository;
    private FraudCaseNoteRepository noteRepository;
    private FraudCaseAuditRepository auditRepository;
    private FraudCaseLifecycleIdempotencyRepository idempotencyRepository;
    private AlertRepository alertRepository;
    private FraudCaseManagementService service;

    @BeforeEach
    void setUp() {
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl("fdp43_failure_" + UUID.randomUUID().toString().replace("-", ""))
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        caseRepository = repositoryFactory.getRepository(FraudCaseRepository.class);
        noteRepository = repositoryFactory.getRepository(FraudCaseNoteRepository.class);
        auditRepository = repositoryFactory.getRepository(FraudCaseAuditRepository.class);
        idempotencyRepository = repositoryFactory.getRepository(FraudCaseLifecycleIdempotencyRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        alertRepository.save(alert("alert-1"));
        service = service(IdempotencyServiceMode.NORMAL);
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
    void idempotencyCompletionSaveFailureRollsBackMutationAndAudit() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(IdempotencyServiceMode.FAIL_COMPLETION_SAVE);

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Completion failure", false, "analyst-1"),
                "completion-fail-key"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency completion save failed");

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(idempotencyRepository.findAll())
                .noneMatch(record -> record.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED);
    }

    @Test
    void oversizedResponseSnapshotFailsClosedAndRollsBackMutationAndAudit() {
        FraudCaseDocument created = createCase();
        Instant originalUpdatedAt = caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt();
        service = service(IdempotencyServiceMode.TINY_SNAPSHOT_LIMIT);

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Snapshot failure", false, "analyst-1"),
                "snapshot-fail-key"
        )).isInstanceOf(FraudCaseIdempotencySnapshotTooLargeException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).isEmpty();
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(idempotencyRepository.findAll()).isEmpty();
    }

    private FraudCaseDocument createCase() {
        return service.createCase(new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ));
    }

    private long countAudit(String caseId, FraudCaseAuditAction action) {
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(entry -> entry.getAction() == action)
                .count();
    }

    private FraudCaseManagementService service(IdempotencyServiceMode mode) {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        var scoredTransactionRepository = repositoryFactory.getRepository(ScoredTransactionRepository.class);
        var searchRepository = new MongoFraudCaseSearchRepository(mongoTemplate);
        var actorResolver = new AnalystActorResolver(new CurrentAnalystUser(), metrics);
        var transactionRunner = transactionRunner();
        var responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        var idempotencyService = idempotencyService(mode, transactionRunner);
        return new FraudCaseManagementService(
                caseRepository,
                scoredTransactionRepository,
                actorResolver,
                new FraudCaseUpdateMutationHandler(caseRepository, metrics),
                unusedRegulatedMutationCoordinator(),
                responseMapper,
                new FraudCaseLifecycleService(
                        caseRepository,
                        alertRepository,
                        noteRepository,
                        repositoryFactory.getRepository(com.frauddetection.alert.persistence.FraudCaseDecisionRepository.class),
                        actorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        idempotencyService
                ),
                new FraudCaseQueryService(caseRepository, auditRepository, searchRepository, responseMapper)
        );
    }

    private FraudCaseLifecycleIdempotencyService idempotencyService(
            IdempotencyServiceMode mode,
            RegulatedMutationTransactionRunner transactionRunner
    ) {
        var keyPolicy = new SharedIdempotencyKeyPolicy();
        var conflictPolicy = new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy());
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        if (mode == IdempotencyServiceMode.FAIL_COMPLETION_SAVE) {
            return new CompletionFailingFraudCaseLifecycleIdempotencyService(
                    idempotencyRepository,
                    keyPolicy,
                    conflictPolicy,
                    transactionRunner,
                    objectMapper
            );
        }
        return new FraudCaseLifecycleIdempotencyService(
                idempotencyRepository,
                keyPolicy,
                conflictPolicy,
                transactionRunner,
                objectMapper,
                mode == IdempotencyServiceMode.TINY_SNAPSHOT_LIMIT
                        ? 8
                        : FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES
        );
    }

    private RegulatedMutationTransactionRunner transactionRunner() {
        return new RegulatedMutationTransactionRunner(
                "REQUIRED",
                new FixedTransactionManagerProvider(new MongoTransactionManager(databaseFactory))
        );
    }

    private RegulatedMutationCoordinator unusedRegulatedMutationCoordinator() {
        return new RegulatedMutationCoordinator() {
            @Override
            public <R, S> RegulatedMutationResult<S> commit(RegulatedMutationCommand<R, S> command) {
                throw new AssertionError("FDP-43 local lifecycle operations must not use RegulatedMutationCoordinator.");
            }
        };
    }

    private AlertDocument alert(String alertId) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-transaction");
        document.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        return document;
    }

    private enum IdempotencyServiceMode {
        NORMAL,
        FAIL_COMPLETION_SAVE,
        TINY_SNAPSHOT_LIMIT
    }

    private static final class CompletionFailingFraudCaseLifecycleIdempotencyService extends FraudCaseLifecycleIdempotencyService {
        private CompletionFailingFraudCaseLifecycleIdempotencyService(
                FraudCaseLifecycleIdempotencyRepository repository,
                SharedIdempotencyKeyPolicy keyPolicy,
                FraudCaseLifecycleIdempotencyConflictPolicy conflictPolicy,
                RegulatedMutationTransactionRunner transactionRunner,
                JsonMapper objectMapper
        ) {
            super(repository, keyPolicy, conflictPolicy, transactionRunner, objectMapper, MAX_RESPONSE_SNAPSHOT_BYTES);
        }

        @Override
        protected FraudCaseLifecycleIdempotencyRecordDocument saveRecord(FraudCaseLifecycleIdempotencyRecordDocument record) {
            if (record.getStatus() == FraudCaseLifecycleIdempotencyStatus.COMPLETED) {
                throw new IllegalStateException("idempotency completion save failed");
            }
            return super.saveRecord(record);
        }
    }

    private static final class FixedTransactionManagerProvider implements ObjectProvider<PlatformTransactionManager> {
        private final PlatformTransactionManager transactionManager;

        private FixedTransactionManagerProvider(PlatformTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
        }

        @Override
        public PlatformTransactionManager getObject(Object... args) throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getIfAvailable() throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getIfUnique() throws BeansException {
            return transactionManager;
        }

        @Override
        public PlatformTransactionManager getObject() throws BeansException {
            return transactionManager;
        }

        @Override
        public Iterator<PlatformTransactionManager> iterator() {
            return List.of(transactionManager).iterator();
        }

        @Override
        public Stream<PlatformTransactionManager> stream() {
            return Stream.of(transactionManager);
        }
    }
}
