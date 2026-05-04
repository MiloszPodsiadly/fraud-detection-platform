package com.frauddetection.alert.audit;

import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
@Tag("evidence-gated-finalize")
class RegulatedMutationLocalAuditPhaseWriterIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private AuditEventRepository auditEventRepository;
    private AuditAnchorRepository auditAnchorRepository;
    private AuditChainLockRepository lockRepository;
    private SimpleMeterRegistry meterRegistry;
    private AlertServiceMetrics metrics;
    private LocalAuditPhaseWriterProperties properties;
    private RegulatedMutationLocalAuditPhaseWriter writer;

    @BeforeEach
    void setUp() {
        String databaseName = "fdp29_local_audit_writer_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        new AuditChainIndexInitializer(mongoTemplate).ensureIndexes();
        auditEventRepository = new AuditEventRepository(mongoTemplate);
        auditAnchorRepository = new AuditAnchorRepository(mongoTemplate);
        lockRepository = new AuditChainLockRepository(mongoTemplate);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AlertServiceMetrics(meterRegistry);
        properties = fastProperties();
        writer = writer(auditEventRepository, auditAnchorRepository, lockRepository, properties);
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
    void recordSuccessPhase_isIdempotentByPhaseKey() {
        RegulatedMutationCommandDocument command = command("command-idempotent", "idem-idempotent", "alert-idempotent");

        String firstAuditId = writer.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);
        String secondAuditId = writer.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);

        assertThat(secondAuditId).isEqualTo(firstAuditId);
        assertThat(countEvents(command.getId() + ":SUCCESS")).isEqualTo(1);
        AuditEventDocument event = auditEventRepository.findByRequestId(command.getId() + ":SUCCESS").orElseThrow();
        assertThat(countAnchors(event.eventHash())).isEqualTo(1);
        assertContinuousChain(auditEventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "SUCCESS", 1.0d);
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "DUPLICATE_PHASE", 1.0d);
        assertThat(meterRegistry.get("fdp29_local_audit_chain_append_duration_ms").timer().count()).isEqualTo(2L);
    }

    @Test
    void duplicatePhaseKeyRace_returnsExistingAuditIdWithoutChainFork() throws Exception {
        RegulatedMutationCommandDocument command = command("command-race", "idem-race", "alert-race");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return writer.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);
                }));
            }
            start.countDown();

            String firstAuditId = futures.get(0).get(30, TimeUnit.SECONDS);
            String secondAuditId = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(secondAuditId).isEqualTo(firstAuditId);
            assertThat(countEvents(command.getId() + ":SUCCESS")).isEqualTo(1);
            assertThat(auditEventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10))
                    .hasSize(1)
                    .extracting(AuditEventDocument::chainPosition)
                    .containsExactly(1L);
            AuditEventDocument event = auditEventRepository.findByRequestId(command.getId() + ":SUCCESS").orElseThrow();
            assertThat(countAnchors(event.eventHash())).isEqualTo(1);
            assertCounter("fdp29_local_audit_chain_append_total", "outcome", "DUPLICATE_PHASE", 1.0d);
        }
    }

    @Test
    void auditInsertFailure_failsClosedWithoutOrphanAnchor() {
        RegulatedMutationLocalAuditPhaseWriter failingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                new FailingAuditEventRepository(mongoTemplate),
                auditAnchorRepository,
                lockRepository,
                metrics,
                properties
        );

        assertThatThrownBy(() -> failingWriter.recordSuccessPhase(
                command("command-audit-fail", "idem-audit-fail", "alert-audit-fail"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        )).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "AUDIT_INSERT_FAILED", 1.0d);
    }

    @Test
    void anchorInsertFailure_rollsBackWhenInsideTransaction() {
        RegulatedMutationLocalAuditPhaseWriter failingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                auditEventRepository,
                new FailingAuditAnchorRepository(mongoTemplate),
                lockRepository,
                metrics,
                properties
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(new MongoTransactionManager(databaseFactory));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> failingWriter.recordSuccessPhase(
                command("command-anchor-fail", "idem-anchor-fail", "alert-anchor-fail"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        ))).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "ANCHOR_INSERT_FAILED", 1.0d);

        String recoveredAuditId = writer.recordSuccessPhase(
                command("command-after-anchor-rollback", "idem-after-anchor-rollback", "alert-after-anchor-rollback"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );

        assertThat(recoveredAuditId).isNotBlank();
        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isEqualTo(1L);
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isEqualTo(1L);
        assertContinuousChain(auditEventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10));
    }

    @Test
    void lockReleaseFailure_doesNotTurnFailureIntoDuplicateSuccess() {
        RegulatedMutationCommandDocument command = command("command-release-fail", "idem-release-fail", "alert-release-fail");
        RegulatedMutationLocalAuditPhaseWriter releaseFailingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                auditEventRepository,
                auditAnchorRepository,
                new ReleaseFailingAuditChainLockRepository(mongoTemplate),
                metrics,
                properties
        );

        String firstAuditId = releaseFailingWriter.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);
        String secondAuditId = releaseFailingWriter.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);

        assertThat(secondAuditId).isEqualTo(firstAuditId);
        assertThat(countEvents(command.getId() + ":SUCCESS")).isEqualTo(1);
        AuditEventDocument event = auditEventRepository.findByRequestId(command.getId() + ":SUCCESS").orElseThrow();
        assertThat(countAnchors(event.eventHash())).isEqualTo(1);
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "LOCK_RELEASE_FAILED", 1.0d);
        assertCounter("fdp29_local_audit_chain_lock_release_failure_total", 1.0d);
    }

    @Test
    void staleLockExpiresAndNextWriterCanAppend() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-04T10:00:00Z"));
        AuditChainLockRepository staleAwareLockRepository = new AuditChainLockRepository(mongoTemplate, clock);
        staleAwareLockRepository.acquire(AuditEventDocument.PARTITION_KEY, "stale-owner");
        RegulatedMutationLocalAuditPhaseWriter staleAwareWriter = writer(
                auditEventRepository,
                auditAnchorRepository,
                staleAwareLockRepository,
                oneAttemptProperties()
        );

        assertThatThrownBy(() -> staleAwareWriter.recordSuccessPhase(
                command("command-while-stale-lock-active", "idem-while-stale-lock-active", "alert-while-stale-lock-active"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        )).isInstanceOf(AuditPersistenceUnavailableException.class);

        clock.advanceSeconds(31);
        String auditId = staleAwareWriter.recordSuccessPhase(
                command("command-after-stale-lock-expired", "idem-after-stale-lock-expired", "alert-after-stale-lock-expired"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        );

        assertThat(auditId).isNotBlank();
        assertThat(auditEventRepository.findFullChain(AuditEventDocument.PARTITION_KEY, 10))
                .hasSize(1)
                .extracting(AuditEventDocument::chainPosition)
                .containsExactly(1L);
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "CHAIN_CONFLICT_EXHAUSTED", 1.0d);
    }

    @Test
    void boundedChainConflictRetryExhaustionIsObservable() {
        RegulatedMutationLocalAuditPhaseWriter alwaysLockedWriter = writer(
                auditEventRepository,
                auditAnchorRepository,
                new AlwaysLockedAuditChainLockRepository(mongoTemplate),
                twoAttemptProperties()
        );

        assertThatThrownBy(() -> alwaysLockedWriter.recordSuccessPhase(
                command("command-chain-conflict", "idem-chain-conflict", "alert-chain-conflict"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        )).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "CHAIN_CONFLICT_RETRY", 1.0d);
        assertCounter("fdp29_local_audit_chain_append_total", "outcome", "CHAIN_CONFLICT_EXHAUSTED", 1.0d);
        assertCounter("fdp29_local_audit_chain_retry_total", "reason", "LOCK_CONFLICT", 1.0d);
    }

    private RegulatedMutationCommandDocument command(String commandId, String idempotencyKey, String alertId) {
        RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
        command.setId(commandId);
        command.setIdempotencyKey(idempotencyKey);
        command.setActorId("principal-7");
        command.setResourceId(alertId);
        command.setResourceType(AuditResourceType.ALERT.name());
        command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        command.setCorrelationId("corr-" + alertId);
        command.setCreatedAt(Instant.parse("2026-05-04T00:00:00Z"));
        command.setUpdatedAt(Instant.parse("2026-05-04T00:00:00Z"));
        return command;
    }

    private long countEvents(String requestId) {
        return mongoTemplate.count(Query.query(Criteria.where("request_id").is(requestId)), AuditEventDocument.class);
    }

    private long countAnchors(String eventHash) {
        return mongoTemplate.count(Query.query(Criteria.where("last_event_hash").is(eventHash)), AuditAnchorDocument.class);
    }

    private RegulatedMutationLocalAuditPhaseWriter writer(
            AuditEventRepository eventRepository,
            AuditAnchorRepository anchorRepository,
            AuditChainLockRepository auditChainLockRepository,
            LocalAuditPhaseWriterProperties writerProperties
    ) {
        return new RegulatedMutationLocalAuditPhaseWriter(
                eventRepository,
                anchorRepository,
                auditChainLockRepository,
                metrics,
                writerProperties
        );
    }

    private LocalAuditPhaseWriterProperties fastProperties() {
        LocalAuditPhaseWriterProperties writerProperties = new LocalAuditPhaseWriterProperties();
        writerProperties.setMaxAppendAttempts(50);
        writerProperties.setBackoffMs(1);
        writerProperties.setMaxTotalWaitMs(500);
        return writerProperties;
    }

    private LocalAuditPhaseWriterProperties oneAttemptProperties() {
        LocalAuditPhaseWriterProperties writerProperties = new LocalAuditPhaseWriterProperties();
        writerProperties.setMaxAppendAttempts(1);
        writerProperties.setBackoffMs(1);
        writerProperties.setMaxTotalWaitMs(50);
        return writerProperties;
    }

    private LocalAuditPhaseWriterProperties twoAttemptProperties() {
        LocalAuditPhaseWriterProperties writerProperties = new LocalAuditPhaseWriterProperties();
        writerProperties.setMaxAppendAttempts(2);
        writerProperties.setBackoffMs(1);
        writerProperties.setMaxTotalWaitMs(50);
        return writerProperties;
    }

    private void assertCounter(String name, double expected) {
        assertThat(meterRegistry.get(name).counter().count()).isEqualTo(expected);
    }

    private void assertCounter(String name, String tagKey, String tagValue, double expected) {
        assertThat(meterRegistry.find(name).tag(tagKey, tagValue).counter())
                .describedAs(name + "{" + tagKey + "=" + tagValue + "}")
                .isNotNull();
        assertThat(meterRegistry.get(name).tag(tagKey, tagValue).counter().count()).isEqualTo(expected);
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

    private static final class FailingAuditEventRepository extends AuditEventRepository {
        private FailingAuditEventRepository(MongoTemplate mongoTemplate) {
            super(mongoTemplate);
        }

        @Override
        public AuditEventDocument insert(AuditEventDocument document) throws DataAccessException {
            throw new DataAccessResourceFailureException("audit insert failed");
        }
    }

    private static final class FailingAuditAnchorRepository extends AuditAnchorRepository {
        private FailingAuditAnchorRepository(MongoTemplate mongoTemplate) {
            super(mongoTemplate);
        }

        @Override
        public AuditAnchorDocument insert(AuditAnchorDocument document) throws DataAccessException {
            throw new DataAccessResourceFailureException("anchor insert failed");
        }
    }

    private static final class ReleaseFailingAuditChainLockRepository extends AuditChainLockRepository {
        private ReleaseFailingAuditChainLockRepository(MongoTemplate mongoTemplate) {
            super(mongoTemplate);
        }

        @Override
        public void release(String partitionKey, String ownerToken) throws DataAccessException {
            throw new DataAccessResourceFailureException("lock release failed");
        }
    }

    private static final class AlwaysLockedAuditChainLockRepository extends AuditChainLockRepository {
        private AlwaysLockedAuditChainLockRepository(MongoTemplate mongoTemplate) {
            super(mongoTemplate);
        }

        @Override
        public AuditChainLockDocument acquire(String partitionKey, String ownerToken) throws DataAccessException {
            throw new AuditChainConflictException("always locked");
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

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
