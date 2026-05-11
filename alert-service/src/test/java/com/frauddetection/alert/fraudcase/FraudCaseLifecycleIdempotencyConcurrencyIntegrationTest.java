package com.frauddetection.alert.fraudcase;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.alert.api.AddFraudCaseDecisionRequest;
import com.frauddetection.alert.api.AddFraudCaseNoteRequest;
import com.frauddetection.alert.api.CreateFraudCaseRequest;
import com.frauddetection.alert.api.FraudCaseDecisionResponse;
import com.frauddetection.alert.api.FraudCaseNoteResponse;
import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.domain.FraudCaseAuditAction;
import com.frauddetection.alert.domain.FraudCaseDecisionType;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private FraudCaseRepository caseRepository;
    private FraudCaseNoteRepository noteRepository;
    private FraudCaseDecisionRepository decisionRepository;
    private FraudCaseAuditRepository auditRepository;
    private FraudCaseLifecycleIdempotencyRepository idempotencyRepository;
    private AlertRepository alertRepository;
    private FraudCaseManagementService service;

    @BeforeEach
    void setUp() {
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl("fdp43_concurrency_" + UUID.randomUUID().toString().replace("-", ""))
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        MongoRepositoryFactory repositoryFactory = new MongoRepositoryFactory(mongoTemplate);
        caseRepository = repositoryFactory.getRepository(FraudCaseRepository.class);
        noteRepository = repositoryFactory.getRepository(FraudCaseNoteRepository.class);
        decisionRepository = repositoryFactory.getRepository(FraudCaseDecisionRepository.class);
        auditRepository = repositoryFactory.getRepository(FraudCaseAuditRepository.class);
        idempotencyRepository = repositoryFactory.getRepository(FraudCaseLifecycleIdempotencyRepository.class);
        alertRepository = repositoryFactory.getRepository(AlertRepository.class);
        service = service();
        alertRepository.save(alert("alert-1"));
        alertRepository.save(alert("alert-2"));
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
    void concurrentSameKeyAddNoteCannotMutateTwice() throws Exception {
        FraudCaseDocument created = createCase();
        AddFraudCaseNoteRequest request = new AddFraudCaseNoteRequest("Concurrent note", true, "analyst-1");

        List<ConcurrentResult<?>> results = runConcurrently(() -> service.addNote(created.getCaseId(), request, "note-race-key"));

        assertAllowedRaceOutcome(results, FraudCaseNoteResponse.class, responses ->
                assertThat(responses).extracting(FraudCaseNoteResponse::id).containsOnly(responses.getFirst().id())
        );
        assertThat(noteRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.NOTE_ADDED)).isEqualTo(1);
        assertOneCompletedRecord("ADD_FRAUD_CASE_NOTE", created.getCaseId());
    }

    @Test
    void concurrentSameKeyAddDecisionCannotMutateTwice() throws Exception {
        FraudCaseDocument created = createCase();
        AddFraudCaseDecisionRequest request = new AddFraudCaseDecisionRequest(
                FraudCaseDecisionType.NEEDS_MORE_INFO,
                "Concurrent decision",
                "analyst-1"
        );

        List<ConcurrentResult<?>> results = runConcurrently(() -> service.addDecision(created.getCaseId(), request, "decision-race-key"));

        assertAllowedRaceOutcome(results, FraudCaseDecisionResponse.class, responses ->
                assertThat(responses).extracting(FraudCaseDecisionResponse::id).containsOnly(responses.getFirst().id())
        );
        assertThat(decisionRepository.findByCaseIdOrderByCreatedAtAsc(created.getCaseId())).hasSize(1);
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.DECISION_ADDED)).isEqualTo(1);
        assertOneCompletedRecord("ADD_FRAUD_CASE_DECISION", created.getCaseId());
    }

    @Test
    void concurrentSameKeyCreateCaseCannotMutateTwice() throws Exception {
        CreateFraudCaseRequest request = new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Concurrent create",
                "analyst-1"
        );

        List<ConcurrentResult<?>> results = runConcurrently(() -> service.createCase(request, "create-race-key"));

        assertAllowedRaceOutcome(results, FraudCaseResponse.class, responses -> {
            assertThat(responses).extracting(FraudCaseResponse::caseId).containsOnly(responses.getFirst().caseId());
            assertThat(responses).extracting(FraudCaseResponse::caseNumber).containsOnly(responses.getFirst().caseNumber());
        });
        assertThat(caseRepository.findAll()).hasSize(1);
        FraudCaseDocument created = caseRepository.findAll().getFirst();
        assertThat(countAudit(created.getCaseId(), FraudCaseAuditAction.CASE_CREATED)).isEqualTo(1);
        assertOneCompletedRecord("CREATE_FRAUD_CASE", "CREATE");
    }

    private List<ConcurrentResult<?>> runConcurrently(Callable<?> callable) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentResult<?>> first = executor.submit(racingCallable(callable, ready, start));
            Future<ConcurrentResult<?>> second = executor.submit(racingCallable(callable, ready, start));
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<ConcurrentResult<?>> racingCallable(
            Callable<?> callable,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                return ConcurrentResult.success(callable.call());
            } catch (Throwable throwable) {
                return ConcurrentResult.failure(throwable);
            }
        };
    }

    private <T> void assertAllowedRaceOutcome(
            List<ConcurrentResult<?>> results,
            Class<T> responseType,
            Consumer<List<T>> replayStabilityAssertion
    ) {
        List<T> successes = results.stream()
                .filter(ConcurrentResult::isSuccess)
                .map(ConcurrentResult::value)
                .map(responseType::cast)
                .toList();
        List<Throwable> failures = results.stream()
                .filter(result -> !result.isSuccess())
                .map(ConcurrentResult::error)
                .toList();
        assertThat(results).hasSize(2);
        assertThat(successes).hasSizeBetween(1, 2);
        assertThat(failures).hasSize(2 - successes.size());
        assertThat(failures).allSatisfy(error ->
                assertThat(error)
                        .as("same-key loser must resolve to an idempotency-domain race outcome")
                        .isInstanceOf(FraudCaseIdempotencyInProgressException.class)
        );
        if (successes.size() == 2) {
            replayStabilityAssertion.accept(successes);
        }
    }

    private FraudCaseDocument createCase() {
        FraudCaseResponse response = service.createCase(new CreateFraudCaseRequest(
                List.of("alert-1"),
                FraudCasePriority.HIGH,
                RiskLevel.CRITICAL,
                "Manual investigation",
                "analyst-1"
        ), "create-helper-key-" + UUID.randomUUID());
        return caseRepository.findById(response.caseId()).orElseThrow();
    }

    private long countAudit(String caseId, FraudCaseAuditAction action) {
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(caseId).stream()
                .filter(entry -> entry.getAction() == action)
                .count();
    }

    private void assertOneCompletedRecord(String action, String caseIdScope) {
        assertThat(idempotencyRepository.findAll())
                .filteredOn(record -> action.equals(record.getAction()) && caseIdScope.equals(record.getCaseIdScope()))
                .hasSize(1)
                .first()
                .extracting(record -> record.getStatus())
                .isEqualTo(FraudCaseLifecycleIdempotencyStatus.COMPLETED);
    }

    private FraudCaseManagementService service() {
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        var scoredTransactionRepository = new MongoRepositoryFactory(mongoTemplate).getRepository(ScoredTransactionRepository.class);
        var searchRepository = new MongoFraudCaseSearchRepository(mongoTemplate);
        var actorResolver = new AnalystActorResolver(new CurrentAnalystUser(), metrics);
        var transactionRunner = transactionRunner();
        var responseMapper = new FraudCaseResponseMapper(new AlertResponseMapper());
        var idempotencyService = new FraudCaseLifecycleIdempotencyService(
                idempotencyRepository,
                new SharedIdempotencyKeyPolicy(),
                new FraudCaseLifecycleIdempotencyConflictPolicy(new SharedIdempotencyConflictPolicy()),
                transactionRunner,
                JsonMapper.builder().addModule(new JavaTimeModule()).build()
        );
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
                        decisionRepository,
                        actorResolver,
                        transactionRunner,
                        new FraudCaseTransitionPolicy(),
                        new FraudCaseAuditService(auditRepository),
                        idempotencyService
                ),
                new FraudCaseQueryService(
                        caseRepository,
                        auditRepository,
                        searchRepository,
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

    private record ConcurrentResult<T>(T value, Throwable error) {
        static <T> ConcurrentResult<T> success(T value) {
            return new ConcurrentResult<>(value, null);
        }

        static <T> ConcurrentResult<T> failure(Throwable error) {
            return new ConcurrentResult<>(null, error);
        }

        boolean isSuccess() {
            return error == null;
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
