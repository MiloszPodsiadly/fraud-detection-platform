package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.CloseFraudCaseRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.idempotency.SharedIdempotencyKeyPolicy;
import com.frauddetection.alert.mapper.AlertResponseMapper;
import com.frauddetection.alert.mapper.FraudCaseResponseMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseAuditEntryDocument;
import com.frauddetection.alert.persistence.FraudCaseAuditRepository;
import com.frauddetection.alert.persistence.FraudCaseDecisionRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRepository;
import com.frauddetection.alert.persistence.FraudCaseNoteDocument;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest extends AbstractIntegrationTest {

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
                FraudPlatformContainers.mongodb().getReplicaSetUrl("fdp43_global_key_" + UUID.randomUUID().toString().replace("-", ""))
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        caseRepository = repositoryFactory.getRepository(FraudCaseRepository.class);
        noteRepository = repositoryFactory.getRepository(FraudCaseNoteRepository.class);
        auditRepository = repositoryFactory.getRepository(FraudCaseAuditRepository.class);
        idempotencyRepository = repositoryFactory.getRepository(FraudCaseLifecycleIdempotencyRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        alertRepository.save(alert("alert-1"));
        alertRepository.save(alert("alert-2"));
        service = service(repositoryFactory);
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
    void shouldRejectSameKeyForDifferentActionWithoutMutationAuditOrSecondRecord() {
        FraudCaseDocument created = createCase("alert-1");
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("Initial note", true, "analyst-1"), "pr50-same-key");

        assertThatThrownBy(() -> service.closeCase(
                created.getCaseId(),
                new CloseFraudCaseRequest("Attempted close with reused key", "analyst-1"),
                "pr50-same-key"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(caseRepository.findById(created.getCaseId()).orElseThrow().getStatus()).isNotEqualTo(FraudCaseStatus.CLOSED);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_CLOSED)).isZero();
        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void sameKeyDifferentCaseScopeMustConflictWithoutMutationOrAudit() {
        FraudCaseDocument firstCase = createCase("alert-1");
        FraudCaseDocument secondCase = createCase("alert-2");
        service.addNote(firstCase.getCaseId(), new AddFraudCaseNoteRequest("Case one note", true, "analyst-1"), "pr50-scope-key");

        assertThatThrownBy(() -> service.addNote(
                secondCase.getCaseId(),
                new AddFraudCaseNoteRequest("Case two note", true, "analyst-1"),
                "pr50-scope-key"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(secondCase.getCaseId())).isEmpty();
        assertThat(countAudit(secondCase.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isZero();
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void sameKeyDifferentActorMustConflictWithoutMutationOrAudit() {
        FraudCaseDocument created = createCase("alert-1");
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("Actor-owned note", true, "analyst-a"), "pr50-actor-key");

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Actor-owned note", true, "analyst-b"),
                "pr50-actor-key"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void sameKeyDifferentPayloadSameClaimMustConflictWithoutMutationOrAudit() {
        FraudCaseDocument created = createCase("alert-1");
        service.addNote(created.getCaseId(), new AddFraudCaseNoteRequest("Payload A", true, "analyst-1"), "pr50-payload-key");

        assertThatThrownBy(() -> service.addNote(
                created.getCaseId(),
                new AddFraudCaseNoteRequest("Payload B", true, "analyst-1"),
                "pr50-payload-key"
        )).isInstanceOf(FraudCaseIdempotencyConflictException.class);

        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId()))
                .extracting(FraudCaseNoteDocument::getBody)
                .containsExactly("Payload A");
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    @Test
    void sameKeySameClaimMustReplay() {
        FraudCaseDocument created = createCase("alert-1");
        AddFraudCaseNoteRequest request = new AddFraudCaseNoteRequest("Replay note", true, "analyst-1");

        var first = service.addNote(created.getCaseId(), request, "pr50-replay-key");
        var replay = service.addNote(created.getCaseId(), request, "pr50-replay-key");

        assertThat(replay).isEqualTo(first);
        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertThat(idempotencyRepository.findAll()).hasSize(1);
    }

    private FraudCaseDocument createCase(String alertId) {
        FraudCaseDocument created = service.createCase(new CreateFraudCaseRequest(
                List.of(alertId),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ), "create-helper-key-" + UUID.randomUUID());
        idempotencyRepository.deleteAll();
        return created;
    }

    private FraudCaseManagementService service(MongoRepositoryFactory repositoryFactory) {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var transactionRunner = transactionRunner();
        var responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        var actorResolver = new AnalystActorResolver(new CurrentAnalystUser(), metrics);
        var idempotencyService = new FraudCaseLifecycleIdempotencyService(
                idempotencyRepository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                FraudCaseLifecycleIdempotencyService.MAX_RESPONSE_SNAPSHOT_BYTES,
                FraudCaseLifecycleIdempotencyService.DEFAULT_RETENTION,
                metrics,
                Clock.systemUTC()
        );
        return new FraudCaseManagementService(
                caseRepository,
                repositoryFactory.getRepository(ScoredTransactionRepository.class),
                actorResolver,
                new FraudCaseUpdateMutationHandler(caseRepository, metrics),
                unusedRegulatedMutationCoordinator(),
                responseMapper,
                new FraudCaseLifecycleService(
                        caseRepository,
                        alertRepository,
                        noteRepository,
                        repositoryFactory.getRepository(FraudCaseDecisionRepository.class),
                        actorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        idempotencyService
                ),
                new FraudCaseQueryService(
                        caseRepository,
                        auditRepository,
                        new MongoFraudCaseSearchRepository(mongoTemplate),
                        responseMapper
                )
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
                throw new AssertionError("FDP-43 global-key regression path must not use RegulatedMutationCoordinator.");
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

    private long countAudit(String caseId, FraudCaseAuditAction action) {
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(entry -> entry.getAction() == action)
                .count();
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

        public Class<?> getObjectType() {
            return PlatformTransactionManager.class;
        }
    }
}
