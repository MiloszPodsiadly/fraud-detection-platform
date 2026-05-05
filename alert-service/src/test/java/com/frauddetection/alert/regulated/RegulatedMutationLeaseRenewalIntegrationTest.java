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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Tag("invariant-proof")
class RegulatedMutationLeaseRenewalIntegrationTest extends AbstractIntegrationTest {

    private static final Instant START = Instant.parse("2026-05-05T10:00:00Z");

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;
    private MutableClock clock;
    private RegulatedMutationClaimService claimService;
    private RegulatedMutationLeaseRenewalService renewalService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_renewal_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        mongoTemplate.indexOps(RegulatedMutationCommandDocument.class)
                .ensureIndex(new Index().on("idempotency_key", Sort.Direction.ASC).unique());
        clock = new MutableClock(START);
        meterRegistry = new SimpleMeterRegistry();
        AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);
        RegulatedMutationLeaseRenewalPolicy policy = new RegulatedMutationLeaseRenewalPolicy(
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                3
        );
        RegulatedMutationLeaseRenewalFailureHandler failureHandler =
                new RegulatedMutationLeaseRenewalFailureHandler(
                        mongoTemplate,
                        policy,
                        new RegulatedMutationPublicStatusMapper()
                );
        claimService = new RegulatedMutationClaimService(
                mongoTemplate,
                Duration.ofMillis(150),
                metrics,
                clock
        );
        renewalService = new RegulatedMutationLeaseRenewalService(mongoTemplate, policy, failureHandler, metrics, clock);
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
    void currentOwnerRenewsActiveProcessingCommandWithoutBusinessFieldChanges() {
        mongoTemplate.save(commandDocument("idem-renew-current", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-current", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-current"
        ).orElseThrow();
        Instant originalExpiry = token.leaseExpiresAt();
        clock.advance(Duration.ofMillis(100));

        RegulatedMutationLeaseRenewalDecision decision = renewalService.renew(token, Duration.ofMillis(300));

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-current",
                RegulatedMutationCommandDocument.class
        );
        assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.RENEW);
        assertThat(persisted.getLeaseExpiresAt()).isAfter(originalExpiry);
        assertThat(persisted.getLeaseOwner()).isEqualTo(token.leaseOwner());
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.leaseRenewalCountOrZero()).isEqualTo(1);
        assertThat(persisted.getLastLeaseRenewedAt()).isEqualTo(clock.instant());
        assertThat(persisted.getResponseSnapshot()).isNull();
        assertThat(persisted.getOutboxEventId()).isNull();
        assertThat(persisted.getLocalCommitMarker()).isNull();
        assertThat(persisted.getSuccessAuditId()).isNull();
        assertThat(persisted.getPublicStatus()).isNull();
    }

    @Test
    void staleOwnerCannotRenewAfterTakeover() {
        mongoTemplate.save(commandDocument("idem-renew-stale", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationCommand<String, String> command =
                command("idem-renew-stale", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-renew-stale").orElseThrow();
        clock.advance(Duration.ofMillis(151));
        RegulatedMutationClaimToken workerB = claimService.claim(command, "idem-renew-stale").orElseThrow();

        assertThatThrownBy(() -> renewalService.renew(workerA, Duration.ofMillis(300)))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.STALE_LEASE_OWNER);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-stale",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getLeaseOwner()).isEqualTo(workerB.leaseOwner());
        assertThat(persisted.getLeaseOwner()).isNotEqualTo(workerA.leaseOwner());
    }

    @Test
    void expiredOwnerCannotRenew() {
        mongoTemplate.save(commandDocument("idem-renew-expired", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-expired", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-expired"
        ).orElseThrow();
        clock.advance(Duration.ofMillis(151));

        assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPIRED_LEASE);

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-expired",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getLeaseExpiresAt()).isEqualTo(token.leaseExpiresAt());
        assertThat(persisted.leaseRenewalCountOrZero()).isZero();
    }

    @Test
    void terminalStateCannotRenew() {
        mongoTemplate.save(commandDocument("idem-renew-terminal", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-terminal", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-terminal"
        ).orElseThrow();
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-terminal",
                RegulatedMutationCommandDocument.class
        );
        persisted.setState(RegulatedMutationState.COMMITTED);
        mongoTemplate.save(persisted);
        clock.advance(Duration.ofMillis(50));

        assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                .isInstanceOf(RegulatedMutationLeaseRenewalException.class)
                .extracting("reason")
                .isEqualTo(RegulatedMutationLeaseRenewalReason.TERMINAL_STATE);
    }

    @Test
    void budgetExhaustionRejectsRenewal() {
        mongoTemplate.save(commandDocument("idem-renew-budget", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-budget", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-budget"
        ).orElseThrow();
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-budget",
                RegulatedMutationCommandDocument.class
        );
        persisted.setLeaseRenewalCount(3);
        mongoTemplate.save(persisted);
        clock.advance(Duration.ofMillis(50));

        assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                .isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument after = mongoTemplate.findById(
                "command-idem-renew-budget",
                RegulatedMutationCommandDocument.class
        );
        assertThat(after.getLeaseExpiresAt()).isEqualTo(token.leaseExpiresAt());
        assertThat(after.leaseRenewalCountOrZero()).isEqualTo(3);
        assertThat(after.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(after.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(after.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertThat(after.getLastError()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertNoBusinessEvidenceFields(after);
    }

    @Test
    void evidenceGatedActiveStatesRenewThroughRealMongo() {
        for (RegulatedMutationState state : List.of(
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationState.FINALIZING
        )) {
            String idempotencyKey = "idem-renew-fdp29-" + state.name().toLowerCase();
            RegulatedMutationClaimToken token = claimedEvidenceGatedCommand(idempotencyKey, state);
            clock.advance(Duration.ofMillis(50));

            RegulatedMutationLeaseRenewalDecision decision = renewalService.renew(token, Duration.ofMillis(300));

            RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                    "command-" + idempotencyKey,
                    RegulatedMutationCommandDocument.class
            );
            assertThat(decision.type()).isEqualTo(RegulatedMutationLeaseRenewalDecisionType.RENEW);
            assertThat(persisted.getState()).isEqualTo(state);
            assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
            assertThat(persisted.leaseRenewalCountOrZero()).isEqualTo(1);
            assertNoBusinessEvidenceFields(persisted);
        }
    }

    @Test
    void evidenceGatedTerminalAndRecoveryStatesCannotRenew() {
        for (RegulatedMutationState state : List.of(
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED
        )) {
            String idempotencyKey = "idem-renew-fdp29-blocked-" + state.name().toLowerCase();
            RegulatedMutationClaimToken token = claimedEvidenceGatedCommand(idempotencyKey, RegulatedMutationState.FINALIZING);
            RegulatedMutationCommandDocument document = mongoTemplate.findById(
                    "command-" + idempotencyKey,
                    RegulatedMutationCommandDocument.class
            );
            document.setState(state);
            if (state == RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED) {
                document.setExecutionStatus(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
            }
            mongoTemplate.save(document);
            clock.advance(Duration.ofMillis(50));

            assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                    .isInstanceOf(RegulatedMutationLeaseRenewalException.class);
        }
    }

    @Test
    void evidenceGatedBudgetExceededMarksDurableFinalizeRecoveryRequired() {
        RegulatedMutationClaimToken token = claimedEvidenceGatedCommand(
                "idem-renew-fdp29-budget",
                RegulatedMutationState.FINALIZING
        );
        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-fdp29-budget",
                RegulatedMutationCommandDocument.class
        );
        persisted.setLeaseRenewalCount(3);
        mongoTemplate.save(persisted);
        clock.advance(Duration.ofMillis(50));

        assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                .isInstanceOf(RegulatedMutationLeaseRenewalBudgetExceededException.class);

        RegulatedMutationCommandDocument after = mongoTemplate.findById(
                "command-idem-renew-fdp29-budget",
                RegulatedMutationCommandDocument.class
        );
        assertThat(after.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(after.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(after.getPublicStatus().name()).isEqualTo("FINALIZE_RECOVERY_REQUIRED");
        assertThat(after.getDegradationReason()).isEqualTo(RegulatedMutationLeaseRenewalFailureHandler.BUDGET_EXCEEDED_REASON);
        assertNoBusinessEvidenceFields(after);
    }

    @Test
    void legacyCommandMissingRenewalFieldsInitializesThemOnRenewal() {
        RegulatedMutationCommandDocument document = commandDocument(
                "idem-renew-missing-fields",
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
        );
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setLeaseOwner("owner-missing");
        document.setLeaseExpiresAt(START.plusMillis(150));
        document.setLeaseBudgetStartedAt(null);
        document.setLeaseRenewalCount(null);
        mongoTemplate.save(document);
        RegulatedMutationClaimToken token = new RegulatedMutationClaimToken(
                document.getId(),
                "owner-missing",
                START.plusMillis(150),
                START,
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        clock.advance(Duration.ofMillis(100));

        renewalService.renew(token, Duration.ofMillis(300));

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-missing-fields",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.leaseRenewalCountOrZero()).isEqualTo(1);
        assertThat(persisted.getLeaseBudgetStartedAt()).isEqualTo(START);
        assertThat(persisted.getLastLeaseRenewedAt()).isEqualTo(clock.instant());
    }

    @Test
    void leaseExactlyAtExpiryCannotRenew() {
        mongoTemplate.save(commandDocument("idem-renew-at-expiry", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-at-expiry", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-at-expiry"
        ).orElseThrow();
        clock.advance(Duration.ofMillis(150));

        assertThatThrownBy(() -> renewalService.renew(token, Duration.ofMillis(300)))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPIRED_LEASE);
    }

    @Test
    void renewedLeaseBlocksPrematureTakeover() {
        mongoTemplate.save(commandDocument("idem-renew-blocks-takeover", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationCommand<String, String> command =
                command("idem-renew-blocks-takeover", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        RegulatedMutationClaimToken workerA = claimService.claim(command, "idem-renew-blocks-takeover").orElseThrow();
        clock.advance(Duration.ofMillis(100));
        renewalService.renew(workerA, Duration.ofMillis(300));
        clock.advance(Duration.ofMillis(100));

        Optional<RegulatedMutationClaimToken> prematureWorkerB = claimService.claim(command, "idem-renew-blocks-takeover");

        assertThat(prematureWorkerB).isEmpty();
        clock.advance(Duration.ofMillis(201));
        assertThat(claimService.claim(command, "idem-renew-blocks-takeover")).isPresent();
    }

    @Test
    void concurrentRenewalRaceStaysWithinBudget() throws Exception {
        mongoTemplate.save(commandDocument("idem-renew-race", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-race", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-race"
        ).orElseThrow();
        clock.advance(Duration.ofMillis(100));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptRenew(token));
            var second = executor.submit(() -> attemptRenew(token));
            List<Boolean> results = List.of(first.get(), second.get());
            assertThat(results).contains(true);
        }

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-race",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.leaseRenewalCountOrZero()).isBetween(1, 2);
        assertThat(persisted.getLeaseExpiresAt())
                .isBeforeOrEqualTo(persisted.getLeaseBudgetStartedAt().plusSeconds(1));
    }

    @Test
    void concurrentRenewalAtLastAllowedSlotAllowsOnlyOneSuccess() throws Exception {
        mongoTemplate.save(commandDocument("idem-renew-last-slot", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-last-slot", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-last-slot"
        ).orElseThrow();
        RegulatedMutationCommandDocument document = mongoTemplate.findById(
                "command-idem-renew-last-slot",
                RegulatedMutationCommandDocument.class
        );
        document.setLeaseRenewalCount(2);
        mongoTemplate.save(document);
        clock.advance(Duration.ofMillis(100));

        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptRenew(token));
            var second = executor.submit(() -> attemptRenew(token));
            results = List.of(first.get(), second.get());
        }

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-last-slot",
                RegulatedMutationCommandDocument.class
        );
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(persisted.leaseRenewalCountOrZero()).isEqualTo(3);
        assertThat(persisted.getLeaseExpiresAt())
                .isBeforeOrEqualTo(persisted.getLeaseBudgetStartedAt().plusSeconds(1));
        assertThat(persisted.getState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(persisted.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertNoBusinessEvidenceFields(persisted);
        assertThat(meterRegistry.find("regulated_mutation_lease_renewal_total")
                .tag("outcome", "SUCCESS")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("regulated_mutation_lease_renewal_total")
                .tag("outcome", "BUDGET_EXCEEDED")
                .counter()).isNotNull();
    }

    @Test
    void concurrentRenewalNearTotalBudgetDoesNotExtendPastBudgetEnd() throws Exception {
        mongoTemplate.save(commandDocument("idem-renew-budget-edge", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION));
        RegulatedMutationClaimToken token = claimService.claim(
                command("idem-renew-budget-edge", RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION),
                "idem-renew-budget-edge"
        ).orElseThrow();
        clock.advance(Duration.ofMillis(800));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptRenew(token));
            var second = executor.submit(() -> attemptRenew(token));
            first.get();
            second.get();
        }

        RegulatedMutationCommandDocument persisted = mongoTemplate.findById(
                "command-idem-renew-budget-edge",
                RegulatedMutationCommandDocument.class
        );
        assertThat(persisted.getLeaseExpiresAt())
                .isBeforeOrEqualTo(persisted.getLeaseBudgetStartedAt().plusSeconds(1));
        assertNoBusinessEvidenceFields(persisted);
    }

    private boolean attemptRenew(RegulatedMutationClaimToken token) {
        try {
            renewalService.renew(token, Duration.ofMillis(500));
            return true;
        } catch (RegulatedMutationLeaseRenewalException | StaleRegulatedMutationLeaseException ignored) {
            return false;
        }
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
        document.setCreatedAt(clock.instant());
        document.setUpdatedAt(clock.instant());
        return document;
    }

    private RegulatedMutationClaimToken claimedEvidenceGatedCommand(
            String idempotencyKey,
            RegulatedMutationState state
    ) {
        mongoTemplate.save(commandDocument(idempotencyKey, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1));
        RegulatedMutationClaimToken token = claimService.claim(
                command(idempotencyKey, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1),
                idempotencyKey
        ).orElseThrow();
        RegulatedMutationCommandDocument document = mongoTemplate.findById(
                "command-" + idempotencyKey,
                RegulatedMutationCommandDocument.class
        );
        document.setState(state);
        mongoTemplate.save(document);
        return token;
    }

    private void assertNoBusinessEvidenceFields(RegulatedMutationCommandDocument document) {
        assertThat(document.getResponseSnapshot()).isNull();
        assertThat(document.getOutboxEventId()).isNull();
        assertThat(document.getLocalCommitMarker()).isNull();
        assertThat(document.getSuccessAuditId()).isNull();
    }

    private RegulatedMutationCommand<String, String> command(
            String idempotencyKey,
            RegulatedMutationModelVersion modelVersion
    ) {
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
                state -> state.name(),
                null,
                modelVersion
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
    }
}
