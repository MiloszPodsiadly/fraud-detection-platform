package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.index.Index;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Tag("invariant-proof")
class RegulatedMutationLeaseFencingIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationClaimService claimService;
    private RegulatedMutationFencedCommandWriter fencedWriter;

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_fencing_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        AlertServiceMetrics metrics = new AlertServiceMetrics(new SimpleMeterRegistry());
        claimService = new RegulatedMutationClaimService(mongoTemplate, Duration.ofMillis(150), metrics);
        fencedWriter = new RegulatedMutationFencedCommandWriter(mongoTemplate, metrics);
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
    void onlyOneWorkerCanClaimActiveCommand() throws Exception {
        mongoTemplate.save(commandDocument("idem-claim-race", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationCommand<String, String> command = command("idem-claim-race");

        List<Optional<RegulatedMutationClaimToken>> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimService.claim(command, "idem-claim-race"));
            var second = executor.submit(() -> claimService.claim(command, "idem-claim-race"));
            results = List.of(first.get(), second.get());
        }

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-claim-race", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getLeaseOwner()).isNotBlank();
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleWorkerCannotWriteAfterTakeover() throws Exception {
        mongoTemplate.save(commandDocument("idem-takeover", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationCommand<String, String> command = command("idem-takeover");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-takeover").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-takeover").orElseThrow();

        RegulatedMutationCommandDocument current = mongoTemplate.findById("command-idem-takeover", RegulatedMutationCommandDocument.class);
        assertThat(current.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(current.getLeaseOwner()).isNotEqualTo(workerA.leaseOwner());

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("success_audit_recorded", true)
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument afterStaleWrite = mongoTemplate.findById("command-idem-takeover", RegulatedMutationCommandDocument.class);
        assertThat(afterStaleWrite.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(afterStaleWrite.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(afterStaleWrite.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void currentLeaseOwnerCanWriteFencedTransition() {
        mongoTemplate.save(commandDocument("idem-current-owner", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(command("idem-current-owner"), "idem-current-owner").orElseThrow();

        fencedWriter.transition(
                token,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("attempted_audit_recorded", true)
        );

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-current-owner", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(persisted.isAttemptedAuditRecorded()).isTrue();
    }

    @Test
    void staleWorkerCannotWriteEvidenceGatedFinalizedPendingExternal() throws Exception {
        mongoTemplate.save(commandDocument("idem-evidence-stale", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationCommand<String, String> command = command("idem-evidence-stale");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-evidence-stale").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-evidence-stale").orElseThrow();

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                null,
                update -> {
                    update.set("response_snapshot", new RegulatedMutationResponseSnapshot(
                            "alert-1",
                            com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                            com.frauddetection.common.events.enums.AlertStatus.RESOLVED,
                            "event-stale",
                            Instant.now(),
                            com.frauddetection.alert.api.SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
                    ));
                    update.set("outbox_event_id", "event-stale");
                    update.set("local_commit_marker", "EVIDENCE_GATED_FINALIZED");
                    update.set("success_audit_id", "success-stale");
                    update.set("success_audit_recorded", true);
                }
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-evidence-stale", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.getSuccessAuditId()).isNull();
        assertThat(persisted.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void recoveryStateCannotBeOverwrittenByStaleWorker() throws Exception {
        mongoTemplate.save(commandDocument("idem-recovery-stale", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationCommand<String, String> command = command("idem-recovery-stale");
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-recovery-stale").orElseThrow();
        sleepPastLease();
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-recovery-stale").orElseThrow();
        fencedWriter.transition(
                workerB,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                update -> update.set("degradation_reason", "RECOVERY_REQUIRED")
        );

        assertThatThrownBy(() -> fencedWriter.transition(
                workerA,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById("command-idem-recovery-stale", RegulatedMutationCommandDocument.class);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.FAILED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
    }

    private void sleepPastLease() throws InterruptedException {
        Thread.sleep(220);
    }

    private RegulatedMutationCommandDocument commandDocument(String idempotencyKey, RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-" + idempotencyKey);
        document.setIdempotencyKey(idempotencyKey);
        document.setRequestHash("request-hash-" + idempotencyKey);
        document.setMutationModelVersion(modelVersion);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        document.setAttemptCount(0);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
    }

    private RegulatedMutationCommand<String, String> command(String idempotencyKey) {
        return new RegulatedMutationCommand<>(
                idempotencyKey,
                "principal-7",
                "alert-1",
                AuditResourceType.ALERT,
                AuditAction.SUBMIT_ANALYST_DECISION,
                "corr-1",
                "request-hash-" + idempotencyKey,
                context -> "ok",
                (result, state) -> state.name(),
                response -> null,
                snapshot -> "ok",
                state -> state.name()
        );
    }
}
