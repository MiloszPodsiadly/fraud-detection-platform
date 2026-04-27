package com.frauddetection.alert.audit;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class AuditIntegrityMongoStressTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityMongoStressTest.class);
    private static final int LONG_CHAIN_EVENTS = 10_000;

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;
    private AuditEventRepository repository;
    private AuditAnchorRepository anchorRepository;
    private AuditIntegrityService integrityService;

    @BeforeEach
    void setUp() {
        String databaseName = "audit_integrity_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        new AuditChainIndexInitializer(mongoTemplate).ensureIndexes();
        repository = new AuditEventRepository(mongoTemplate);
        anchorRepository = new AuditAnchorRepository(mongoTemplate);
        integrityService = new AuditIntegrityService(
                repository,
                anchorRepository,
                new AuditIntegrityQueryParser(),
                new AlertServiceMetrics(new SimpleMeterRegistry()),
                mock(AuditService.class)
        );
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
    void shouldMaintainLongChainStabilityWithBoundedIntegrityModes() {
        long startedAt = System.nanoTime();
        List<AuditEventDocument> documents = new ArrayList<>(LONG_CHAIN_EVENTS);
        String previousHash = null;
        for (int i = 1; i <= LONG_CHAIN_EVENTS; i++) {
            AuditEventDocument document = document(i, previousHash);
            documents.add(document);
            previousHash = document.eventHash();
        }
        mongoTemplate.insert(documents, AuditEventDocument.class);
        anchorRepository.insert(AuditAnchorDocument.from("anchor-final", documents.getLast()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info("Generated {} durable audit chain records in {} ms.", LONG_CHAIN_EVENTS, elapsedMillis);

        List<AuditEventDocument> fullChain = repository.findFullChain(AuditEventDocument.PARTITION_KEY, LONG_CHAIN_EVENTS);

        assertThat(fullChain).hasSize(LONG_CHAIN_EVENTS);
        assertContinuousChain(fullChain);
        AuditIntegrityResponse head = integrityService.verify(null, null, "alert-service", "HEAD", 500);
        AuditIntegrityResponse window = integrityService.verify("2026-04-26T02:30:00Z", null, "alert-service", "WINDOW", 500);
        AuditIntegrityResponse full = integrityService.verify(null, null, "alert-service", "FULL_CHAIN", LONG_CHAIN_EVENTS);
        assertBoundedPass(head);
        assertBoundedPass(window);
        assertThat(full.status()).isEqualTo("VALID");
        assertThat(full.violations()).isEmpty();
        assertThat(full.checked()).isEqualTo(LONG_CHAIN_EVENTS);
        assertThat(window.externalPredecessor()).isTrue();
    }

    @Test
    void shouldSerializeConcurrentWritesAgainstRealMongoWithoutForks() throws Exception {
        int writers = 20;
        int eventsPerWriter = 5;
        int expectedEvents = writers * eventsPerWriter;
        PersistentAuditEventPublisher publisher = new PersistentAuditEventPublisher(
                repository,
                anchorRepository,
                new AuditChainLockRepository(mongoTemplate),
                new AlertServiceMetrics(new SimpleMeterRegistry())
        );
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(writers);

        try (var executor = Executors.newFixedThreadPool(writers)) {
            for (int writer = 0; writer < writers; writer++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < eventsPerWriter; i++) {
                        int eventNumber = sequence.incrementAndGet();
                        publisher.publish(event(eventNumber));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        List<AuditEventDocument> fullChain = repository.findFullChain(AuditEventDocument.PARTITION_KEY, expectedEvents);

        assertThat(fullChain).hasSize(expectedEvents);
        assertContinuousChain(fullChain);
        assertThat(fullChain.stream().map(AuditEventDocument::chainPosition)).doesNotHaveDuplicates();
        AuditIntegrityResponse response = integrityService.verify(null, null, "alert-service", "HEAD", 500);
        assertThat(response.status()).isEqualTo("VALID");
        assertThat(response.violations()).isEmpty();
    }

    @Test
    void shouldPreventDuplicateChainPositionAtMongoIndexBoundary() {
        AuditEventDocument first = document(1, null);
        AuditEventDocument duplicatePosition = AuditEventDocument.from("audit-duplicate", event(2), first.eventHash(), 1L);
        repository.insert(first);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insert(duplicatePosition))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void assertBoundedPass(AuditIntegrityResponse response) {
        assertThat(response.status()).isIn("VALID", "PARTIAL");
        assertThat(response.violations()).isEmpty();
        assertThat(response.checked()).isLessThanOrEqualTo(response.limit());
    }

    private void assertContinuousChain(List<AuditEventDocument> documents) {
        Set<Long> chainPositions = new HashSet<>();
        AuditEventDocument previous = null;
        for (int index = 0; index < documents.size(); index++) {
            AuditEventDocument current = documents.get(index);
            assertThat(current.chainPosition()).isEqualTo(index + 1L);
            assertThat(chainPositions.add(current.chainPosition())).isTrue();
            if (previous == null) {
                assertThat(current.previousEventHash()).isNull();
            } else {
                assertThat(current.previousEventHash()).isEqualTo(previous.eventHash());
            }
            assertThat(AuditEventHasher.matches(current)).isTrue();
            previous = current;
        }
    }

    private AuditEventDocument document(int index, String previousHash) {
        return AuditEventDocument.from("audit-" + index, event(index), previousHash, index);
    }

    private AuditEvent event(int index) {
        return new AuditEvent(
                new AuditActor("admin-1", Set.of("FRAUD_OPS_ADMIN"), Set.of("audit:read")),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-" + index,
                Instant.parse("2026-04-26T00:00:00Z").plusSeconds(index),
                "corr-" + index,
                AuditOutcome.SUCCESS,
                null
        );
    }
}
