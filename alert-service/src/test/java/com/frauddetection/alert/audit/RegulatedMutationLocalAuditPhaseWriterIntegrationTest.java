package com.frauddetection.alert.audit;

import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
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
        writer = new RegulatedMutationLocalAuditPhaseWriter(auditEventRepository, auditAnchorRepository, lockRepository);
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
        }
    }

    @Test
    void auditInsertFailure_failsClosedWithoutOrphanAnchor() {
        RegulatedMutationLocalAuditPhaseWriter failingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                new FailingAuditEventRepository(mongoTemplate),
                auditAnchorRepository,
                lockRepository
        );

        assertThatThrownBy(() -> failingWriter.recordSuccessPhase(
                command("command-audit-fail", "idem-audit-fail", "alert-audit-fail"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        )).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
    }

    @Test
    void anchorInsertFailure_rollsBackWhenInsideTransaction() {
        RegulatedMutationLocalAuditPhaseWriter failingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                auditEventRepository,
                new FailingAuditAnchorRepository(mongoTemplate),
                lockRepository
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(new MongoTransactionManager(databaseFactory));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> failingWriter.recordSuccessPhase(
                command("command-anchor-fail", "idem-anchor-fail", "alert-anchor-fail"),
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT
        ))).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditAnchorDocument.class)).isZero();
    }

    @Test
    void lockReleaseFailure_doesNotTurnFailureIntoDuplicateSuccess() {
        RegulatedMutationCommandDocument command = command("command-release-fail", "idem-release-fail", "alert-release-fail");
        RegulatedMutationLocalAuditPhaseWriter releaseFailingWriter = new RegulatedMutationLocalAuditPhaseWriter(
                auditEventRepository,
                auditAnchorRepository,
                new ReleaseFailingAuditChainLockRepository(mongoTemplate)
        );

        String firstAuditId = releaseFailingWriter.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);
        String secondAuditId = releaseFailingWriter.recordSuccessPhase(command, AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT);

        assertThat(secondAuditId).isEqualTo(firstAuditId);
        assertThat(countEvents(command.getId() + ":SUCCESS")).isEqualTo(1);
        AuditEventDocument event = auditEventRepository.findByRequestId(command.getId() + ":SUCCESS").orElseThrow();
        assertThat(countAnchors(event.eventHash())).isEqualTo(1);
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
}
