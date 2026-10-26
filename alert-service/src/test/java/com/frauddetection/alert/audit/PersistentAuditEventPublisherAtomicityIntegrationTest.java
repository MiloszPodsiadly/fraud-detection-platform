package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublicationRequiredException;
import com.frauddetection.alert.audit.external.ExternalAuditAnchorPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.mongodb.MongoException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class PersistentAuditEventPublisherAtomicityIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private AuditEventRepository eventRepository;
    private AuditAnchorRepository anchorRepository;
    private AuditChainLockRepository lockRepository;
    private SimpleMeterRegistry meterRegistry;
    private AuditMongoTransactionRunner transactionRunner;

    @BeforeEach
    void setUp() {
        String databaseName = "audit_atomic_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName));
        mongoTemplate = new MongoTemplate(databaseFactory);
        new AuditChainIndexInitializer(mongoTemplate).ensureIndexes();
        eventRepository = new AuditEventRepository(mongoTemplate);
        anchorRepository = new AuditAnchorRepository(mongoTemplate);
        lockRepository = new AuditChainLockRepository(mongoTemplate);
        meterRegistry = new SimpleMeterRegistry();
        transactionRunner = new AuditMongoTransactionRunner(new MongoTransactionManager(databaseFactory));
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
    void eventInsertFailure_writesNeitherEventNorAnchor() {
        PersistentAuditEventPublisher failing = publisher(new FailingAuditEventRepository(mongoTemplate), anchorRepository);

        assertThatThrownBy(() -> failing.publish(event("event-insert-fail")))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertThat(persistedEventMetric()).isZero();
        AuditEvent retry = event("event-insert-retry");
        publisher(eventRepository, anchorRepository).publish(retry);
        assertThat(eventRepository.findByRequestId(retry.requestId()).orElseThrow().chainPosition()).isEqualTo(1L);
    }

    @Test
    void anchorInsertFailure_rollsBackEventAndNextAppendUsesSamePosition() {
        PersistentAuditEventPublisher failing = publisher(eventRepository, new FailingAuditAnchorRepository(mongoTemplate));

        assertThatThrownBy(() -> failing.publish(event("anchor-insert-fail")))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertThat(persistedEventMetric()).isZero();
        AuditEvent retry = event("anchor-insert-retry");
        publisher(eventRepository, anchorRepository).publish(retry);
        AuditEventDocument recovered = eventRepository.findByRequestId(retry.requestId()).orElseThrow();
        assertThat(recovered.chainPosition()).isEqualTo(1L);
        assertThat(anchorRepository.findByPartitionKeyAndChainPositionBetween(
                AuditEventDocument.PARTITION_KEY, 1L, 1L, 2
        )).hasSize(1);
    }

    @Test
    void duplicateRequestAfterLostAcknowledgement_reconcilesExactDurableAppend() {
        PersistentAuditEventPublisher delegate = publisher(eventRepository, anchorRepository);
        AuditEvent event = event("lost-ack-request");
        assertThatThrownBy(() -> {
            delegate.publish(event);
            throw new AuditPersistenceUnavailableException("acknowledgement lost after commit", null);
        }).isInstanceOf(AuditPersistenceUnavailableException.class)
                .hasMessageContaining("acknowledgement lost");

        delegate.publish(event);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
        assertThat(persistedEventMetric()).isEqualTo(1.0d);
        assertCanonicalAppend(eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
    }

    @Test
    void duplicateRequestIdWithDifferentCanonicalTimestampIsRejected() {
        PersistentAuditEventPublisher delegate = publisher(eventRepository, anchorRepository);
        AuditEvent original = event("changed-timestamp-request");
        delegate.publish(original);
        AuditEvent changed = new AuditEvent(original.actor(), original.action(), original.resourceType(),
                original.resourceId(), original.timestamp().plusMillis(1), original.correlationId(),
                original.requestId(), original.outcome(), original.failureCategory(), original.failureReason(),
                original.metadataSummary());

        assertThatThrownBy(() -> delegate.publish(changed))
                .isInstanceOf(AuditPersistenceUnavailableException.class)
                .hasMessageContaining("canonical event identity");

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
    }

    @Test
    void unknownCommitResult_reconcilesEventAndAnchorByRequestIdentity() {
        AtomicBoolean reportUnknownOnce = new AtomicBoolean(true);
        AuditMongoTransactionRunner uncertainCommit = new AuditMongoTransactionRunner(new MongoTransactionManager(databaseFactory)) {
            @Override
            public <T> T required(Supplier<T> work) {
                T result = super.required(work);
                if (reportUnknownOnce.compareAndSet(true, false)) {
                    MongoException unknown = new MongoException("injected unknown transaction commit result");
                    unknown.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
                    throw unknown;
                }
                return result;
            }
        };
        AuditEvent event = event("unknown-commit-request");

        publisher(eventRepository, anchorRepository, uncertainCommit).publish(event);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
        assertThat(persistedEventMetric()).isEqualTo(1.0d);
        assertCanonicalAppend(eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
    }

    @Test
    void transientTransactionFailure_retriesWithoutLeavingPartialEvidence() {
        AtomicBoolean failBeforeTransactionOnce = new AtomicBoolean(true);
        AuditMongoTransactionRunner transientFailure = new AuditMongoTransactionRunner(new MongoTransactionManager(databaseFactory)) {
            @Override
            public <T> T required(Supplier<T> work) {
                if (failBeforeTransactionOnce.compareAndSet(true, false)) {
                    MongoException transientError = new MongoException("injected transient transaction conflict");
                    transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                    throw transientError;
                }
                return super.required(work);
            }
        };
        AuditEvent event = event("transient-transaction-request");

        publisher(eventRepository, anchorRepository, transientFailure).publish(event);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
        assertThat(persistedEventMetric()).isEqualTo(1.0d);
        assertCanonicalAppend(eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
    }

    @Test
    void outerTransactionRollback_removesEventAndAnchorAndDoesNotRecordSuccessMetric() {
        PersistentAuditEventPublisher publisher = publisher(eventRepository, anchorRepository);
        TransactionTemplate transaction = new TransactionTemplate(new MongoTransactionManager(databaseFactory));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            publisher.publish(event("outer-rollback-request"));
            throw new IllegalStateException("rollback outer mutation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertThat(persistedEventMetric()).isZero();
    }

    @Test
    void concurrentDuplicateRequestId_commitsOneCanonicalEventAndAnchor() throws Exception {
        PersistentAuditEventPublisher publisher = publisher(eventRepository, anchorRepository);
        AuditEvent event = event("concurrent-same-request");
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    publisher.publish(event);
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
        assertThat(persistedEventMetric()).isEqualTo(1.0d);
        assertCanonicalAppend(eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
    }

    @Test
    void requiredExternalWitnessUnavailable_keepsLocalEvidenceAndReturnsFailure() {
        ExternalAuditAnchorPublisher external = mock(ExternalAuditAnchorPublisher.class);
        doThrow(new ExternalAuditAnchorPublicationRequiredException("UNAVAILABLE"))
                .when(external).publishRequired(any(AuditAnchorDocument.class));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                eventRepository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                external,
                true,
                true,
                3,
                1L,
                transactionRunner
        );

        assertThatThrownBy(() -> publisher.publish(event("external-unavailable")))
                .isInstanceOf(ExternalAuditAnchorPublicationRequiredException.class);

        List<AuditEventDocument> chain = eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10);
        assertThat(chain).hasSize(2);
        assertThat(chain.getFirst().action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(chain.getLast().action()).isEqualTo(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        assertCanonicalAppend(chain);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(2L);
        verify(external).publishRequired(any(AuditAnchorDocument.class));
    }

    @Test
    void requiredExternalWitnessUnavailableInsideOuterTransaction_commitsCompensationSeparately() {
        ExternalAuditAnchorPublisher external = mock(ExternalAuditAnchorPublisher.class);
        doThrow(new ExternalAuditAnchorPublicationRequiredException("UNAVAILABLE"))
                .when(external).publishRequired(any(AuditAnchorDocument.class));
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                eventRepository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                external,
                true,
                true,
                3,
                1L,
                transactionRunner
        );
        TransactionTemplate outerTransaction = new TransactionTemplate(new MongoTransactionManager(databaseFactory));

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(
                ignored -> publisher.publish(event("external-unavailable-in-outer-transaction"))))
                .isInstanceOf(ExternalAuditAnchorPublicationRequiredException.class);

        List<AuditEventDocument> chain = eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10);
        assertThat(chain).hasSize(2);
        assertThat(chain.getFirst().action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION);
        assertThat(chain.getLast().action()).isEqualTo(AuditAction.EXTERNAL_ANCHOR_REQUIRED_FAILED);
        assertCanonicalAppend(chain);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(2L);
        verify(external).publishRequired(any(AuditAnchorDocument.class));
    }

    private PersistentAuditEventPublisher publisher(AuditEventRepository events, AuditAnchorRepository anchors) {
        return publisher(events, anchors, transactionRunner);
    }

    private PersistentAuditEventPublisher publisher(
            AuditEventRepository events,
            AuditAnchorRepository anchors,
            AuditMongoTransactionRunner runner
    ) {
        return new PersistentAuditEventPublisher(
                events,
                anchors,
                lockRepository,
                new AlertServiceMetrics(meterRegistry),
                null,
                false,
                false,
                2_000,
                5L,
                runner
        );
    }

    private AuditEvent event(String requestId) {
        return new AuditEvent(
                new AuditActor("principal-7", Set.of("FRAUD_OPS_ADMIN"), Set.of("decision:write")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-atomic",
                Instant.parse("2026-10-25T10:00:00Z"),
                "corr-atomic",
                requestId,
                AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE,
                null
        );
    }

    private double persistedEventMetric() {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find("fraud_platform_audit_events_persisted_total")
                .tags("event_type", "submit_analyst_decision", "outcome", "success")
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private void assertCanonicalAppend(List<AuditEventDocument> chain) {
        AuditEventDocument previous = null;
        for (int index = 0; index < chain.size(); index++) {
            AuditEventDocument current = chain.get(index);
            assertThat(current.chainPosition()).isEqualTo(index + 1L);
            assertThat(AuditEventHasher.matches(current)).isTrue();
            if (previous == null) {
                assertThat(current.previousEventHash()).isNull();
            } else {
                assertThat(current.previousEventHash()).isEqualTo(previous.eventHash());
            }
            List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionBetween(
                    AuditEventDocument.PARTITION_KEY, current.chainPosition(), current.chainPosition(), 2
            );
            assertThat(anchors).hasSize(1);
            assertThat(anchors.getFirst().lastEventHash()).isEqualTo(current.eventHash());
            previous = current;
        }
    }

    private final class FailingAuditEventRepository extends AuditEventRepository {
        private FailingAuditEventRepository(MongoTemplate template) {
            super(template);
        }

        @Override
        public AuditEventDocument insert(AuditEventDocument document) {
            throw new DataAccessResourceFailureException("injected event insert failure");
        }
    }

    private final class FailingAuditAnchorRepository extends AuditAnchorRepository {
        private FailingAuditAnchorRepository(MongoTemplate template) {
            super(template);
        }

        @Override
        public AuditAnchorDocument insert(AuditAnchorDocument document) {
            throw new DataAccessResourceFailureException("injected anchor insert failure");
        }
    }

}
