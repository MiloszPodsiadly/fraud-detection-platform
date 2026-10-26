package com.frauddetection.alert.audit.outbox;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.frauddetection.alert.audit.AuditService;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class WriteActionAuditOutboxMongoFencingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private WriteActionAuditOutboxClaimStore claims;
    private String databaseName;

    @BeforeEach
    void setUp() throws Exception {
        databaseName = "audit_outbox_fence_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(MONGO.getReplicaSetUrl(databaseName));
        factory = new SimpleMongoClientDatabaseFactory(client, databaseName);
        mongo = new MongoTemplate(factory);
        claims = new WriteActionAuditOutboxClaimStore(mongo);
        mongo.insert(pending());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
        if (client != null) client.close();
    }

    @Test
    void staleWorkerCannotOverwritePublishedResultAfterLeaseTakeover() {
        var workerA = claims.claimForPublishing("outbox-1", NOW, "worker-A", LEASE).orElseThrow();
        var workerB = claims.claimForPublishing("outbox-1", NOW.plus(LEASE).plusMillis(1), "worker-B", LEASE)
                .orElseThrow();

        assertThat(workerB.getClaimGeneration()).isEqualTo(workerA.getClaimGeneration() + 1);
        assertThat(workerB.getClaimToken()).isNotEqualTo(workerA.getClaimToken());
        assertThat(claims.markPublished(workerB, NOW.plus(LEASE).plusSeconds(2))).isTrue();
        assertThat(claims.markFailed(workerA, NOW.plus(LEASE).plusSeconds(3), "AUDIT_SERVICE_UNAVAILABLE")).isFalse();
        assertThat(claims.markPublished(workerA, NOW.plus(LEASE).plusSeconds(3))).isFalse();

        var durable = mongo.findById("outbox-1", WriteActionAuditOutboxRecord.class);
        assertThat(durable).isNotNull();
        assertThat(durable.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PUBLISHED);
        assertThat(durable.getClaimToken()).isNull();
        assertThat(durable.getAttemptCount()).isZero();
    }

    @Test
    void concurrentInitialClaimsHaveExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAtBarrier(ready, start, "worker-A"));
            var second = executor.submit(() -> claimAtBarrier(ready, start, "worker-B"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
            assertThat(results.stream().map(java.util.Optional.class::cast).filter(java.util.Optional::isPresent)).hasSize(1);
            assertThat(results.stream().map(java.util.Optional.class::cast).filter(java.util.Optional::isEmpty)).hasSize(1);
        }
    }

    @Test
    void pausedPublisherCannotFailRecordAfterTakeoverAndNewWorkerPublication() throws Exception {
        var repository = mock(WriteActionAuditOutboxRepository.class);
        var audit = mock(AuditService.class);
        var candidate = mongo.findById("outbox-1", WriteActionAuditOutboxRecord.class);
        when(repository.findPublishable(any(Instant.class), any(Pageable.class))).thenReturn(List.of(candidate));
        CountDownLatch enteredAudit = new CountDownLatch(1);
        CountDownLatch resumeAudit = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredAudit.countDown();
            if (!resumeAudit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("controlled audit wait expired");
            throw new IllegalStateException("controlled audit failure after takeover");
        }).when(audit).audit(any(), any(), any(), any(), any(), any(), any(), any());
        var workerA = new WriteActionAuditOutboxPublisher(repository, claims, audit, null,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-A", LEASE);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var publishing = executor.submit(() -> workerA.publishPending());
            assertThat(enteredAudit.await(5, TimeUnit.SECONDS)).isTrue();

            var workerBClaim = claims.claimForPublishing("outbox-1", NOW.plus(LEASE).plusMillis(1), "worker-B", LEASE)
                    .orElseThrow();
            assertThat(claims.markPublished(workerBClaim, NOW.plus(LEASE).plusSeconds(2))).isTrue();
            resumeAudit.countDown();

            assertThat(publishing.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            resumeAudit.countDown();
        }

        var durable = mongo.findById("outbox-1", WriteActionAuditOutboxRecord.class);
        assertThat(durable).isNotNull();
        assertThat(durable.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PUBLISHED);
        assertThat(durable.getAttemptCount()).isZero();
        assertThat(durable.getClaimToken()).isNull();
    }

    @Test
    void sameOwnerNewGenerationInvalidatesOldTokenAndExpiredClaimCannotComplete() {
        var first = claims.claimForPublishing("outbox-1", NOW, "same-worker", LEASE).orElseThrow();
        assertThat(claims.markFailed(first, NOW.plus(LEASE), "AUDIT_SERVICE_UNAVAILABLE")).isFalse();
        var second = claims.claimForPublishing("outbox-1", NOW.plus(LEASE).plusMillis(1), "same-worker", LEASE)
                .orElseThrow();

        assertThat(second.getClaimGeneration()).isEqualTo(first.getClaimGeneration() + 1);
        assertThat(second.getClaimToken()).isNotEqualTo(first.getClaimToken());
        assertThat(claims.markFailed(first, NOW.plus(LEASE).plusSeconds(2), "AUDIT_SERVICE_UNAVAILABLE")).isFalse();
        assertThat(claims.markFailed(second, NOW.plus(LEASE).plusSeconds(2), "AUDIT_SERVICE_UNAVAILABLE")).isTrue();
    }

    private Object claimAtBarrier(CountDownLatch ready, CountDownLatch start, String owner) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim start barrier timed out");
        return claims.claimForPublishing("outbox-1", NOW, owner, LEASE);
    }

    private WriteActionAuditOutboxRecord pending() {
        var record = new WriteActionAuditOutboxRecord();
        record.setOutboxId("outbox-1");
        record.setStatus(WriteActionAuditOutboxStatus.PENDING);
        record.setAttemptCount(0);
        record.setMaxAttempts(5);
        record.setCreatedAt(NOW.minusSeconds(10));
        return record;
    }
}
