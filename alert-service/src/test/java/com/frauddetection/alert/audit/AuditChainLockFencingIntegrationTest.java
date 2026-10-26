package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class AuditChainLockFencingIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private AuditEventRepository eventRepository;
    private AuditAnchorRepository anchorRepository;
    private MutableClock clock;
    private AuditChainLockRepository lockRepository;
    private AuditMongoTransactionRunner transactionRunner;

    @BeforeEach
    void setUp() {
        String databaseName = "audit_fencing_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        new AuditChainIndexInitializer(mongoTemplate).ensureIndexes();
        eventRepository = new AuditEventRepository(mongoTemplate);
        anchorRepository = new AuditAnchorRepository(mongoTemplate);
        clock = new MutableClock(Instant.parse("2026-10-25T10:00:00Z"));
        lockRepository = new AuditChainLockRepository(mongoTemplate, clock);
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
    void newGenerationInvalidatesSameOwnerAndStaleReleaseCannotClearCurrentLease() {
        AuditChainLockDocument oldLease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "logical-owner");
        clock.advance(Duration.ofSeconds(31));
        AuditChainLockDocument currentLease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "logical-owner");

        assertThat(currentLease.generation()).isGreaterThan(oldLease.generation());
        lockRepository.release(oldLease);
        assertThatThrownBy(() -> lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "other-owner"))
                .isInstanceOf(AuditChainConflictException.class);
        assertThatThrownBy(() -> transactionRunner.required(() -> {
            lockRepository.fence(oldLease);
            return null;
        }))
                .isInstanceOf(AuditChainConflictException.class);

        lockRepository.release(currentLease);
        assertThat(lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "next-owner").generation())
                .isGreaterThan(currentLease.generation());
    }

    @Test
    void simultaneousAcquisitionsHaveAtMostOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> attempts = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                String owner = "owner-" + i;
                attempts.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try {
                        return lockRepository.acquire(AuditEventDocument.PARTITION_KEY, owner);
                    } catch (AuditChainConflictException conflict) {
                        return conflict;
                    }
                }));
            }
            start.countDown();
            List<Object> results = List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(AuditChainLockDocument.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(AuditChainConflictException.class::isInstance)).hasSize(1);
        }
    }

    @Test
    void staleWriterResumingAfterTakeoverCannotAppendFromItsOldHead() throws Exception {
        AuditChainLockDocument staleLease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "owner-a");
        CountDownLatch readHead = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> staleAppend = executor.submit(() -> transactionRunner.required(() -> {
                eventRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY);
                readHead.countDown();
                await(resume);
                lockRepository.fence(staleLease);
                appendInCurrentTransaction("stale-audit-request");
                return null;
            }));
            assertThat(readHead.await(5, TimeUnit.SECONDS)).isTrue();

            clock.advance(Duration.ofSeconds(31));
            AuditChainLockDocument currentLease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "owner-b");
            AuditEventDocument committed = append(currentLease, "current-audit-request");
            resume.countDown();

            assertThatThrownBy(() -> staleAppend.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(AuditChainConflictException.class);
            List<AuditEventDocument> chain = eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10);
            assertThat(chain).containsExactly(committed);
            assertThat(anchorRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY).orElseThrow().chainPosition())
                    .isEqualTo(1L);
            lockRepository.release(currentLease);
        } finally {
            resume.countDown();
        }
    }

    @Test
    void expiryDuringFencedTransactionCannotLetTakeoverPassBeforeCommit() throws Exception {
        AuditChainLockDocument oldLease = lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "owner-a");
        CountDownLatch fenced = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> append = executor.submit(() -> appendAndPauseBeforeCommit(oldLease, fenced, commit));
            assertThat(fenced.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(31));
            Future<Object> takeover = executor.submit(() -> {
                try {
                    return lockRepository.acquire(AuditEventDocument.PARTITION_KEY, "owner-b");
                } catch (AuditChainConflictException conflict) {
                    return conflict;
                }
            });
            Object beforeCommit = null;
            try {
                beforeCommit = takeover.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // A transaction holding the lock document may serialize the takeover behind its commit.
            }
            if (beforeCommit instanceof AuditChainLockDocument) {
                throw new AssertionError("A new generation acquired the lock before the fenced append committed.");
            }
            if (beforeCommit != null) {
                assertThat(beforeCommit).isInstanceOf(AuditChainConflictException.class);
            }
            commit.countDown();
            append.get(10, TimeUnit.SECONDS);
            if (beforeCommit == null) {
                Object afterCommit = takeover.get(10, TimeUnit.SECONDS);
                assertThat(afterCommit).isInstanceOf(AuditChainLockDocument.class);
                lockRepository.release((AuditChainLockDocument) afterCommit);
            }
            assertThat(eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10)).hasSize(1);
        } finally {
            commit.countDown();
        }
    }

    @Test
    void uniquePositionAndRequestIndexesAreInstalledAndRejectDuplicates() {
        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        assertThat(initializer.hasRequiredUniqueIndexes()).isTrue();
        assertThat(initializer.preflight().indexes())
                .allMatch(check -> check.state() == AuditChainIndexInitializer.IndexState.PRESENT);
        initializer.ensureIndexes();

        List<Document> requestIndexes = mongoTemplate.getCollection("audit_events")
                .listIndexes(Document.class).into(new ArrayList<>()).stream()
                .filter(index -> index.get("key", Document.class).equals(new Document("request_id", 1)))
                .toList();
        assertThat(requestIndexes).singleElement().satisfies(index -> {
            assertThat(index.getBoolean("unique")).isTrue();
            assertThat(index.get("partialFilterExpression"))
                    .isEqualTo(new Document("request_id", new Document("$type", "string")));
        });

        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "event-1")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 1L)
                .append("request_id", "request-1"));
        assertThatThrownBy(() -> mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "event-2")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 1L)
                .append("request_id", "request-2")))
                .isInstanceOf(com.mongodb.MongoWriteException.class);
        assertThatThrownBy(() -> mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "event-3")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 2L)
                .append("request_id", "request-1")))
                .isInstanceOf(com.mongodb.MongoWriteException.class);

        mongoTemplate.getCollection("audit_chain_anchors").insertOne(new Document("_id", "anchor-1")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 1L));
        assertThatThrownBy(() -> mongoTemplate.getCollection("audit_chain_anchors").insertOne(new Document("_id", "anchor-2")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 1L)))
                .isInstanceOf(com.mongodb.MongoWriteException.class);
    }

    @Test
    void refusesUniquePositionIndexWithRestrictivePartialFilter() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_partition_chain_position_uidx");
        mongoTemplate.getCollection("audit_events").createIndex(
                new Document("partition_key", 1).append("chain_position", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
                        .name("audit_position_restrictive_test")
                        .partialFilterExpression(new Document("chain_position", new Document("$gt", 10L))));

        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        assertThat(initializer.hasRequiredUniqueIndexes()).isFalse();
        assertThatThrownBy(initializer::ensureIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INCOMPATIBLE");
    }

    @Test
    void refusesUniquePositionIndexWithReversedKeyOrder() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_partition_chain_position_uidx");
        mongoTemplate.getCollection("audit_events").createIndex(
                new Document("chain_position", 1).append("partition_key", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
                        .name("audit_position_reversed_test")
                        .partialFilterExpression(new Document("partition_key", new Document("$exists", true))
                                .append("chain_position", new Document("$exists", true))));

        var position = new AuditChainIndexInitializer(mongoTemplate).preflight().indexes().stream()
                .filter(check -> check.collection().equals("audit_events")
                        && check.purpose().equals("chain-position"))
                .findFirst().orElseThrow();
        assertThat(position.state()).isEqualTo(AuditChainIndexInitializer.IndexState.INCOMPATIBLE);
    }

    @Test
    void refusesUniqueRequestIdIndexWithRestrictivePartialFilter() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_request_id_uidx");
        mongoTemplate.getCollection("audit_events").createIndex(
                new Document("request_id", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
                        .name("audit_request_restrictive_test")
                        .partialFilterExpression(new Document("request_id", "only-this-request")));

        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        assertThat(initializer.hasRequiredUniqueIndexes()).isFalse();
        assertThatThrownBy(initializer::ensureIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INCOMPATIBLE");
    }

    @Test
    void refusesRequestIdIndexWithNonDefaultCollation() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_request_id_uidx");
        mongoTemplate.getCollection("audit_events").createIndex(
                new Document("request_id", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
                        .name("audit_request_collation_test")
                        .partialFilterExpression(new Document("request_id", new Document("$type", "string")))
                        .collation(com.mongodb.client.model.Collation.builder()
                                .locale("en")
                                .collationStrength(com.mongodb.client.model.CollationStrength.PRIMARY)
                                .build()));

        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        assertThat(initializer.hasRequiredUniqueIndexes()).isFalse();
        assertThatThrownBy(initializer::ensureIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INCOMPATIBLE");
    }

    @Test
    void legacySparseRequestIdIndexIsReportedWithoutAutomaticMigration() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_request_id_uidx");
        mongoTemplate.getCollection("audit_events").createIndex(
                new Document("request_id", 1),
                new com.mongodb.client.model.IndexOptions().unique(true).sparse(true)
                        .name("audit_request_id_idx"));
        long eventCountBefore = mongoTemplate.getCollection("audit_events").countDocuments();

        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        var requestId = initializer.preflight().indexes().stream()
                .filter(check -> check.purpose().equals("request-id")).findFirst().orElseThrow();
        assertThat(requestId.state()).isEqualTo(AuditChainIndexInitializer.IndexState.LEGACY_SPARSE);
        assertThat(requestId.diagnostic()).contains("operator-approved maintenance migration");
        assertThatThrownBy(initializer::ensureIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEGACY_SPARSE");

        assertThat(mongoTemplate.getCollection("audit_events").countDocuments()).isEqualTo(eventCountBefore);
        assertThat(mongoTemplate.getCollection("audit_events").listIndexes(Document.class)
                .into(new ArrayList<>()).stream().map(index -> index.getString("name")))
                .contains("audit_request_id_idx")
                .doesNotContain("audit_request_id_uidx");
    }

    @Test
    void duplicateRequestIdsAreReportedWithoutCreatingAnIndexOrChangingDocuments() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_request_id_uidx");
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "duplicate-1")
                .append("request_id", "duplicate-request"));
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "duplicate-2")
                .append("request_id", "duplicate-request"));
        long eventCountBefore = mongoTemplate.getCollection("audit_events").countDocuments();

        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        var requestId = initializer.preflight().indexes().stream()
                .filter(check -> check.purpose().equals("request-id")).findFirst().orElseThrow();
        assertThat(requestId.state()).isEqualTo(AuditChainIndexInitializer.IndexState.DUPLICATE_DATA);
        assertThat(requestId.duplicatesDetected()).isTrue();
        assertThatThrownBy(initializer::ensureIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE_DATA");

        assertThat(mongoTemplate.getCollection("audit_events").countDocuments()).isEqualTo(eventCountBefore);
        assertThat(mongoTemplate.getCollection("audit_events").listIndexes(Document.class)
                .into(new ArrayList<>()).stream().map(index -> index.getString("name")))
                .doesNotContain("audit_request_id_uidx");
    }

    @Test
    void concurrentInitializersConvergeOnOneCanonicalIndexSet() throws Exception {
        // This disposable Testcontainers database starts without application indexes.
        mongoTemplate.getCollection("audit_events").dropIndexes();
        mongoTemplate.getCollection("audit_chain_anchors").dropIndexes();
        AuditChainIndexInitializer initializer = new AuditChainIndexInitializer(mongoTemplate);
        assertThat(initializer.preflight().indexes())
                .allMatch(check -> check.state() == AuditChainIndexInitializer.IndexState.MISSING);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                await(start);
                initializer.ensureIndexes();
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                initializer.ensureIndexes();
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(initializer.hasRequiredUniqueIndexes()).isTrue();
        assertThat(mongoTemplate.getCollection("audit_events").listIndexes(Document.class)
                .into(new ArrayList<>()).stream()
                .filter(index -> index.get("key", Document.class).equals(new Document("request_id", 1))))
                .hasSize(1);
    }

    @Test
    void refusesToInstallPositionIndexOverConflictingDurableRows() {
        mongoTemplate.getCollection("audit_events").dropIndex("audit_partition_chain_position_uidx");
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "conflict-1")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 7L)
                .append("request_id", "conflict-request-1"));
        mongoTemplate.getCollection("audit_events").insertOne(new Document("_id", "conflict-2")
                .append("partition_key", AuditEventDocument.PARTITION_KEY)
                .append("chain_position", 7L)
                .append("request_id", "conflict-request-2"));

        assertThatThrownBy(() -> new AuditChainIndexInitializer(mongoTemplate).ensureIndexes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE_DATA");
        assertThat(new AuditChainIndexInitializer(mongoTemplate).hasRequiredUniqueIndexes()).isFalse();
    }

    @Test
    void regularAndRegulatedAppendsShareOneFencedChain() throws Exception {
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                eventRepository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                transactionRunner
        );
        LocalAuditPhaseWriterProperties properties = new LocalAuditPhaseWriterProperties();
        properties.setMaxAppendAttempts(100);
        properties.setBackoffMs(2);
        properties.setMaxTotalWaitMs(2_000);
        RegulatedMutationLocalAuditPhaseWriter regulatedWriter = new RegulatedMutationLocalAuditPhaseWriter(
                eventRepository,
                anchorRepository,
                lockRepository,
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                properties,
                transactionRunner
        );
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> regular = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 10; i++) {
                    publisher.publish(event("regular-request-" + i));
                }
            });
            Future<?> regulated = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 10; i++) {
                    regulatedWriter.recordSuccessPhase(command(i), AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);
                }
            });
            start.countDown();
            regular.get(30, TimeUnit.SECONDS);
            regulated.get(30, TimeUnit.SECONDS);
        }

        List<AuditEventDocument> chain = eventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 25);
        assertThat(chain).hasSize(20);
        for (int i = 0; i < chain.size(); i++) {
            AuditEventDocument current = chain.get(i);
            assertThat(current.chainPosition()).isEqualTo(i + 1L);
            assertThat(AuditEventHasher.matches(current)).isTrue();
            if (i > 0) {
                assertThat(current.previousEventHash()).isEqualTo(chain.get(i - 1).eventHash());
            }
        }
        assertThat(mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), AuditAnchorDocument.class))
                .isEqualTo(20L);
    }

    private AuditEventDocument append(AuditChainLockDocument lease, String requestId) {
        return transactionRunner.required(() -> {
            lockRepository.fence(lease);
            return appendInCurrentTransaction(requestId);
        });
    }

    private AuditEventDocument appendInCurrentTransaction(String requestId) {
        AuditEventDocument previous = eventRepository.findLatestByPartitionKey(AuditEventDocument.PARTITION_KEY).orElse(null);
        long position = previous == null ? 1L : previous.chainPosition() + 1L;
        AuditEventDocument event = AuditEventDocument.from(UUID.randomUUID().toString(), event(requestId),
                previous == null ? null : previous.eventHash(), position);
        eventRepository.insert(event);
        anchorRepository.insert(AuditAnchorDocument.from("anchor-" + requestId, event));
        return event;
    }

    private void appendAndPauseBeforeCommit(AuditChainLockDocument lease, CountDownLatch fenced, CountDownLatch commit) {
        transactionRunner.required(() -> {
            lockRepository.fence(lease);
            appendInCurrentTransaction("near-expiry-request");
            fenced.countDown();
            await(commit);
            return null;
        });
    }

    private AuditEvent event(String requestId) {
        return new AuditEvent(new AuditActor("audit-test-actor", Set.of("ANALYST"), Set.of()),
                AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE, requestId,
                clock.instant(), "corr-" + requestId, requestId, AuditOutcome.SUCCESS,
                AuditFailureCategory.NONE, null);
    }

    private RegulatedMutationCommandDocument command(int index) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId("regulated-command-" + index);
        command.setIdempotencyKey("regulated-idem-" + index);
        command.setActorId("regulated-actor");
        command.setResourceId("regulated-alert-" + index);
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("regulated-corr-" + index);
        command.setCreatedAt(clock.instant());
        command.setUpdatedAt(clock.instant());
        return command;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test synchronization interrupted.", exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
