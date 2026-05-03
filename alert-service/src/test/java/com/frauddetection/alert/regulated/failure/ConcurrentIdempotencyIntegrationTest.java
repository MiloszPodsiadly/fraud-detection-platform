package com.frauddetection.alert.regulated.failure;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.MongoRegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationAuditPhaseService;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.RegulatedMutationTransactionRunner;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("failure-injection")
@Tag("invariant-proof")
@Tag("integration")
class ConcurrentIdempotencyIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (mongoClientDatabaseFactory != null) {
            mongoClientDatabaseFactory.destroy();
        }
    }

    @Test
    void shouldNotDuplicateBusinessMutationUnderConcurrentSameIdempotencyKey() throws Exception {
        RegulatedMutationCommandRepository commandRepository = mongoBackedCommandRepository();
        AuditService auditService = mock(AuditService.class);
        MongoRegulatedMutationCoordinator coordinator = new MongoRegulatedMutationCoordinator(
                commandRepository,
                mongoTemplate,
                new RegulatedMutationAuditPhaseService(new AuditEventRepository(mongoTemplate), auditService),
                mock(AuditDegradationService.class),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                new RegulatedMutationTransactionRunner(
                        "REQUIRED",
                        provider(new MongoTransactionManager(mongoClientDatabaseFactory))
                ),
                true,
                Duration.ofSeconds(30)
        );
        AtomicInteger businessWrites = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        RegulatedMutationCommand<String, String> command = command(businessWrites, mutationEntered, releaseMutation);

        List<RegulatedMutationResult<String>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> commitAfterStart(coordinator, command, start));
            var second = executor.submit(() -> commitAfterStart(coordinator, command, start));
            start.countDown();
            assertThat(mutationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50L);
            releaseMutation.countDown();
            results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }

        assertThat(businessWrites).hasValue(1);
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), TransactionalOutboxRecordDocument.class)).isEqualTo(1L);
        RegulatedMutationCommandDocument persisted = commandRepository.findByIdempotencyKey("idem-concurrent").orElseThrow();
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(persisted.isSuccessAuditRecorded()).isTrue();
        assertThat(results).extracting(RegulatedMutationResult::response)
                .contains("EVIDENCE_PENDING", "REQUESTED");
        verify(auditService, times(1)).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-concurrent"),
                eq("corr-concurrent"),
                eq("ops-admin"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(),
                any()
        );
    }

    private RegulatedMutationResult<String> commitAfterStart(
            MongoRegulatedMutationCoordinator coordinator,
            RegulatedMutationCommand<String, String> command,
            CountDownLatch start
    ) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return coordinator.commit(command);
    }

    private RegulatedMutationCommand<String, String> command(
            AtomicInteger businessWrites,
            CountDownLatch mutationEntered,
            CountDownLatch releaseMutation
    ) {
        return new RegulatedMutationCommand<>(
                "idem-concurrent",
                "ops-admin",
                "alert-concurrent",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-concurrent",
                "request-hash-concurrent",
                context -> {
                    businessWrites.incrementAndGet();
                    mutationEntered.countDown();
                    boolean released;
                    try {
                        released = releaseMutation.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    if (!released) {
                        throw new IllegalStateException("test mutation release timed out");
                    }
                    mongoTemplate.insert(alert());
                    mongoTemplate.insert(outbox());
                    return "event-concurrent";
                },
                (result, state) -> state.name(),
                response -> new RegulatedMutationResponseSnapshot(
                        "alert-concurrent",
                        AnalystDecision.CONFIRMED_FRAUD,
                        AlertStatus.RESOLVED,
                        "event-concurrent",
                        Instant.parse("2026-05-03T10:00:00Z"),
                        SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
                ),
                snapshot -> snapshot == null ? "MISSING" : "RESTORED:" + snapshot.decisionEventId(),
                RegulatedMutationState::name
        );
    }

    private AlertDocument alert() {
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-concurrent");
        document.setTransactionId("txn-concurrent");
        document.setAlertStatus(AlertStatus.RESOLVED);
        document.setCreatedAt(Instant.parse("2026-05-03T10:00:00Z"));
        document.setDecidedAt(Instant.parse("2026-05-03T10:00:00Z"));
        return document;
    }

    private TransactionalOutboxRecordDocument outbox() {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-concurrent");
        document.setDedupeKey("event-concurrent");
        document.setMutationCommandId("command-concurrent");
        document.setResourceType("ALERT");
        document.setResourceId("alert-concurrent");
        document.setEventType("FRAUD_DECISION");
        document.setPayloadHash("payload-hash");
        document.setStatus(TransactionalOutboxStatus.PENDING);
        document.setCreatedAt(Instant.parse("2026-05-03T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-03T10:00:00Z"));
        return document;
    }

    private RegulatedMutationCommandRepository mongoBackedCommandRepository() {
        RegulatedMutationCommandRepository repository = mock(RegulatedMutationCommandRepository.class);
        when(repository.findByIdempotencyKey(any())).thenAnswer(invocation -> {
            String idempotencyKey = invocation.getArgument(0);
            Query query = Query.query(Criteria.where("idempotency_key").is(idempotencyKey));
            return Optional.ofNullable(mongoTemplate.findOne(query, RegulatedMutationCommandDocument.class));
        });
        when(repository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> mongoTemplate.save(invocation.getArgument(0)));
        return repository;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PlatformTransactionManager> provider(PlatformTransactionManager value) {
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
