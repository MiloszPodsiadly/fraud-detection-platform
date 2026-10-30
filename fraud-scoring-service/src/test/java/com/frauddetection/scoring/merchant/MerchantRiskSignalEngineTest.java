package com.frauddetection.scoring.merchant;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.WindowCount;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.engine.FraudSignalEvaluation;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.frauddetection.scoring.merchant.MerchantRiskSignalReasonCode.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantRiskSignalEngineTest {
    private static final Instant TIME = Instant.parse("2027-01-15T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final ScopeClaim SCOPE = new ScopeClaim(token(1), token(2), token(3), token(4), token(5), 1);
    private static final OccurrenceClaim OCCURRENCE = new OccurrenceClaim(token(6), token(7), token(8), 10, TIME, token(9));

    @Test
    void descriptorIsOptionalAndTheExistingRegistryAndPublicV2RejectThisCandidate() {
        try (var engine = new MerchantRiskSignalEngine()) {
            assertThat(engine.descriptor().engineId()).isEqualTo("merchant.primary");
            assertThat(engine.descriptor().engineType()).isEqualTo(FraudEngineType.MERCHANT_RISK);
            assertThat(engine.descriptor().engineLanguage()).isEqualTo("java");
            assertThat(engine.descriptor().version()).isEqualTo("merchant-v1");
            assertThat(engine.descriptor().required()).isFalse();
            assertThatThrownBy(() -> new FraudSignalEngineRegistry(List.of(engine))).hasMessage("ENGINE_REGISTRY_UNKNOWN_ENGINE_ID");
            assertThatThrownBy(() -> new EngineIntelligenceEngineResult(engine.descriptor().engineId(), FraudEngineType.MERCHANT_RISK,
                    FraudEngineStatus.UNAVAILABLE, null, EngineIntelligenceScoreBucket.UNAVAILABLE, List.of()))
                    .hasMessage("ENGINE_INTELLIGENCE_UNKNOWN_ENGINE_ID");
        }
        assertThat(FraudEngineIdentityContract.engineOrder()).containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
        assertThat(FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION).isEqualTo(2);
        assertThat(FraudEngineIdentityContract.maxEngineIntelligenceEngines()).isEqualTo(4);
        assertThat(FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_SERIALIZED_BYTES).isEqualTo(16_384);
    }

    @Test
    void defaultCandidateNeverDerivesAuthorityFromRawMerchantCustomerOrCorrelationClaims() {
        ScoringContext context = context();
        try (var engine = new MerchantRiskSignalEngine()) {
            FraudSignalEvaluation result = engine.evaluate(context);
            unavailable(result, MERCHANT_NOT_ADMITTED);
            assertThat(result).isEqualTo(engine.evaluate(context));
            assertThat(result.toString()).doesNotContain(context.transaction().merchantInfo().merchantId(),
                    context.transaction().customerId(), context.correlationId(), token(1), token(5));
        }
    }

    @Test
    void retrievingAWellFormedProfileDoesNotAuthorizeTheScoringOccurrence() {
        var read = read(profile(0));
        try (var engine = candidate(context -> read)) {
            unavailable(engine.evaluate(context()), MERCHANT_NOT_ADMITTED);
        }
    }

    @Test
    void revokedOccurrencePermissionIsRecheckedOnRepeatedInspectionInsteadOfReusingAnEarlierGrant() {
        ScoringContext context = context();
        var delegate = new TestOnlyAuthorizedAccess(context, read(profile(0)));
        var permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
        MerchantProfileAccess access = new MerchantProfileAccess() {
            public ProfileRead read(ScoringContext current) { return delegate.read(current); }
            public void requireOccurrence(ScoringContext current, ScopeClaim scope, OccurrenceClaim occurrence) {
                if (!permitted.get()) { throw new SecurityException("TEST_ONLY_REVOKED"); }
                delegate.requireOccurrence(current, scope, occurrence);
            }
            public void requireProfile(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) {
                delegate.requireProfile(scope, occurrence, profile);
            }
        };
        try (var engine = candidate(access)) {
            assertThat(engine.evaluate(context).status()).isEqualTo(FraudEngineStatus.DEGRADED);
            permitted.set(false);
            for (int attempt = 0; attempt < 20; attempt++) { unavailable(engine.evaluate(context), MERCHANT_NOT_ADMITTED); }
            assertThat(delegate.profileChecks.get()).isEqualTo(1);
        }
    }

    @Test
    void occurrencePermissionAloneCannotPromoteAProfileToTrustedEvidence() {
        ScoringContext context = context();
        MerchantProfileAccess access = new MerchantProfileAccess() {
            public ProfileRead read(ScoringContext ignored) { return MerchantRiskSignalEngineTest.read(profile(0)); }
            public void requireOccurrence(ScoringContext actual, ScopeClaim scope, OccurrenceClaim occurrence) {
                if (actual != context || !SCOPE.equals(scope) || !OCCURRENCE.equals(occurrence)) { throw new SecurityException(); }
            }
        };
        try (var engine = candidate(access)) {
            unavailable(engine.evaluate(context), MERCHANT_NOT_ADMITTED);
        }
    }

    @Test
    void admittedPositiveAndNegativeFactsRemainDiagnosticsWithoutScoreRiskOrContributions() throws Exception {
        for (int count : new int[] {0, 1, 25_000}) {
            ScoringContext context = context();
            TransactionEnrichedEvent original = context.transaction();
            var access = new TestOnlyAuthorizedAccess(context, read(profile(count)));
            try (var engine = candidate(access)) {
                FraudSignalEvaluation result = engine.evaluate(context);
                assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
                assertThat(result.statusReason()).isEqualTo(MERCHANT_SCORE_NOT_CALIBRATED.wireValue());
                noRisk(result);
                assertThat(result.evidence()).hasSize(5);
                assertThat(result.evidence().subList(0, 4)).allSatisfy(evidence -> assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE));
                assertThat(result.evidence().getLast().status()).isEqualTo(FraudEngineEvidenceStatus.AVAILABLE);
                assertThat(result.evidence().getLast().reasonCode()).isEqualTo(count == 0
                        ? MERCHANT_FIRST_OBSERVED_IN_WINDOW.wireValue() : MERCHANT_PREVIOUSLY_OBSERVED_IN_WINDOW.wireValue());
                assertThat(result.evidence()).allSatisfy(evidence -> assertThat(evidence.evidenceType()).isEqualTo(FraudEngineEvidenceType.MERCHANT_SIGNAL));
                assertThat(engine.evaluate(context)).isEqualTo(result);
                assertThat(context.transaction()).isEqualTo(original);
                assertThat(context.featureSnapshot()).containsExactlyEntriesOf(Map.of("merchantFrequency7d", 99));
                assertThat(access.occurrenceChecks.get()).isEqualTo(2);
                assertThat(access.profileChecks.get()).isEqualTo(2);
                String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(result);
                assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
                assertThat(json).doesNotContain(context.correlationId(), original.customerId(), original.merchantInfo().merchantId());
                for (int id = 1; id <= 10; id++) { assertThat(json).doesNotContain(token(id)); }
            }
        }
    }

    @Test
    void identicalCallerFieldsCannotReuseAnotherIndependentlyAuthorizedOccurrence() {
        ScoringContext authorized = context();
        var access = new TestOnlyAuthorizedAccess(authorized, read(profile(0)));
        ScoringContext other = new ScoringContext(authorized.transaction(), authorized.featureSnapshot(), authorized.mode(), authorized.correlationId(), authorized.receivedAt());
        assertThat(other).isEqualTo(authorized);
        try (var engine = candidate(access)) {
            unavailable(engine.evaluate(other), MERCHANT_NOT_ADMITTED);
            assertThat(access.profileChecks.get()).isZero();
            assertThat(engine.evaluate(authorized).status()).isEqualTo(FraudEngineStatus.DEGRADED);
        }
    }

    @Test
    void authorizationCannotBypassIndependentScopeAndOccurrenceShapeChecks() {
        ScoringContext context = context();
        ScopeClaim foreign = new ScopeClaim(SCOPE.issuerRef(), token(99), SCOPE.sourceRef(), SCOPE.sourceEpochRef(), SCOPE.merchantRef(), 1);
        var foreignRead = new MerchantProfileAccess.ProfileRead(foreign, OCCURRENCE, profile(0));
        try (var engine = candidate(new TestOnlyAuthorizedAccess(context, foreignRead))) {
            unavailable(engine.evaluate(context), MERCHANT_SCOPE_MISMATCH);
        }
        OccurrenceClaim other = new OccurrenceClaim(token(6), token(7), token(99), 10, TIME, token(9));
        try (var engine = candidate(new TestOnlyAuthorizedAccess(context, new MerchantProfileAccess.ProfileRead(SCOPE, other, profile(0))))) {
            unavailable(engine.evaluate(context), MERCHANT_OCCURRENCE_MISMATCH);
        }
    }

    @Test
    void unavailableRecentHistoryCannotReturnAFalseOrCalibratedResult() {
        ScoringContext context = context();
        MerchantProfile incomplete = profile(unavailableWindow(TIME.minus(WINDOW), TIME, MerchantProfile.Reason.MISSING_COVERAGE),
                unavailableWindow(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), MerchantProfile.Reason.MISSING_COVERAGE));
        try (var engine = candidate(new TestOnlyAuthorizedAccess(context, read(incomplete)))) {
            FraudSignalEvaluation result = engine.evaluate(context);
            assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
            assertThat(result.statusReason()).isEqualTo(MERCHANT_SIGNALS_UNAVAILABLE.wireValue());
            assertThat(result.evidence()).allSatisfy(evidence -> assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE));
            assertThat(result.evidence().getLast().reasonCode()).isEqualTo(MERCHANT_MISSING_COVERAGE.wireValue());
            noRisk(result);
        }
    }

    @Test
    void missingBaselineDoesNotHideTheIndependentRecentFactOrInventASpike() {
        ScoringContext context = context();
        MerchantProfile partial = profile(available(TIME.minus(WINDOW), TIME, 0),
                unavailableWindow(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), MerchantProfile.Reason.RETENTION_EXPIRED));
        try (var engine = candidate(new TestOnlyAuthorizedAccess(context, read(partial)))) {
            FraudSignalEvaluation result = engine.evaluate(context);
            assertThat(result.status()).isEqualTo(FraudEngineStatus.DEGRADED);
            assertThat(result.evidence().get(1).reasonCode()).isEqualTo(MERCHANT_RETENTION_EXPIRED.wireValue());
            assertThat(result.evidence().getLast().reasonCode()).isEqualTo(MERCHANT_FIRST_OBSERVED_IN_WINDOW.wireValue());
            noRisk(result);
        }
    }

    @Test
    void globalIntegrityFailuresAndUnexpectedWindowSemanticsRejectTheCandidateProfile() {
        ScoringContext context = context();
        for (MerchantProfile.Reason reason : List.of(MerchantProfile.Reason.SOURCE_GAP, MerchantProfile.Reason.CAPACITY_EXCEEDED,
                MerchantProfile.Reason.DATA_CONFLICT, MerchantProfile.Reason.HISTORY_MISSING, MerchantProfile.Reason.INVALID_TIME)) {
            MerchantProfile invalid = profile(available(TIME.minus(WINDOW), TIME, 0),
                    unavailableWindow(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), reason));
            try (var engine = candidate(new TestOnlyAuthorizedAccess(context, read(invalid)))) {
                unavailable(engine.evaluate(context), MerchantRiskSignalReasonCode.fromProfile(reason));
            }
        }
        try (var engine = new MerchantRiskSignalEngine(new TestOnlyAuthorizedAccess(context, read(profile(0))),
                WINDOW.minusMillis(1), WINDOW, Duration.ofSeconds(2))) {
            unavailable(engine.evaluate(context), MERCHANT_WINDOW_MISMATCH);
        }
    }

    @Test
    void missingAndMalformedReadsHaveBoundedNonInformativeDispositions() {
        try (var engine = candidate(context -> null)) {
            unavailable(engine.evaluate(context()), MERCHANT_NO_PROFILE);
        }
        try (var engine = candidate(context -> new MerchantProfileAccess.ProfileRead(SCOPE, OCCURRENCE, null))) {
            unavailable(engine.evaluate(context()), MERCHANT_DATA_CONFLICT);
        }
    }

    @Test
    void storeAndPermissionErrorsNeverLeakExternalMessagesOrCauses() {
        String secret = "test-only-issuer-secret";
        for (Exception failure : List.of(new IllegalStateException(secret, new RuntimeException(token(99))), new SecurityException(secret))) {
            try (var engine = candidate(context -> { throw failure; })) {
                FraudSignalEvaluation result = engine.evaluate(context());
                unavailable(result, failure instanceof SecurityException ? MERCHANT_NOT_ADMITTED : MERCHANT_PROFILE_ACCESS_UNAVAILABLE);
                assertThat(result.toString()).doesNotContain(secret, token(99));
            }
        }
    }

    @Test
    void declaredAdapterTimeoutIsUnavailableWithoutExposingItsMessage() {
        try (var engine = candidate(context -> { throw new TimeoutException("sensitive-store-location"); })) {
            FraudSignalEvaluation result = engine.evaluate(context());
            unavailable(result, MERCHANT_PROFILE_ACCESS_TIMEOUT);
            assertThat(result.toString()).doesNotContain("sensitive-store-location");
        }
    }

    @Test
    void aBlockedReadIsActuallyCancelledAtTheExecutionDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        MerchantProfileAccess blocking = context -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) { interrupted.countDown(); }
            return null;
        };
        try (var engine = new MerchantRiskSignalEngine(blocking, WINDOW, WINDOW, Duration.ofMillis(250))) {
            unavailable(engine.evaluate(context()), MERCHANT_PROFILE_ACCESS_TIMEOUT);
            assertThat(entered.getCount()).isZero();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aNonCooperatingAdapterCannotAccumulateUnboundedThreadsOrQueuedReads() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        MerchantProfileAccess nonCooperating = context -> {
            reads.incrementAndGet();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulates a dependency that ignores cancellation. */ }
            }
            return null;
        };
        try (var engine = new MerchantRiskSignalEngine(nonCooperating, WINDOW, WINDOW, Duration.ofMillis(250))) {
            try {
                unavailable(engine.evaluate(context()), MERCHANT_PROFILE_ACCESS_TIMEOUT);
                unavailable(engine.evaluate(context()), MERCHANT_PROFILE_ACCESS_TIMEOUT);
                for (int rejected = 0; rejected < 10; rejected++) {
                    unavailable(engine.evaluate(context()), MERCHANT_PROFILE_ACCESS_REJECTED);
                }
                assertThat(reads.get()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void closedCandidatesRejectWorkAndExplicitDeadlinesMustRespectExistingExecutorLimits() {
        var engine = candidate(context -> null);
        engine.close();
        unavailable(engine.evaluate(context()), MERCHANT_PROFILE_ACCESS_REJECTED);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(6))) {
            assertThatThrownBy(() -> new MerchantRiskSignalEngine(context -> null, WINDOW, WINDOW, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static MerchantRiskSignalEngine candidate(MerchantProfileAccess access) {
        return new MerchantRiskSignalEngine(access, WINDOW, WINDOW, Duration.ofSeconds(2));
    }
    private static MerchantProfileAccess.ProfileRead read(MerchantProfile profile) { return new MerchantProfileAccess.ProfileRead(SCOPE, OCCURRENCE, profile); }
    private static void unavailable(FraudSignalEvaluation result, MerchantRiskSignalReasonCode reason) {
        assertThat(result.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(result.statusReason()).isEqualTo(reason.wireValue());
        assertThat(result.reasonCodes()).containsOnly(reason.wireValue());
        assertThat(result.evidence()).allSatisfy(evidence -> assertThat(evidence.status()).isEqualTo(FraudEngineEvidenceStatus.UNAVAILABLE));
        noRisk(result);
    }
    private static void noRisk(FraudSignalEvaluation result) {
        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(result.contributions()).isEmpty();
        assertThat(result.modelName()).isNull();
        assertThat(result.modelVersion()).isNull();
        assertThat(result.featureContractVersion()).isNull();
        assertThat(result.sourceInferenceTimestamp()).isNull();
        assertThat(result.modelArtifactSha256()).isNull();
    }
    private static MerchantProfile profile(int recentCount) {
        return profile(available(TIME.minus(WINDOW), TIME, recentCount), available(TIME.minus(WINDOW).minus(WINDOW), TIME.minus(WINDOW), 0));
    }
    private static MerchantProfile profile(WindowCount recent, WindowCount baseline) {
        return new MerchantProfile(1, 1, SCOPE, OCCURRENCE, new MerchantProfile.Provenance(TIME.plusSeconds(60),
                TIME.minusSeconds(10800), TIME.minusSeconds(10800), TIME, 10L, token(10)), recent, baseline);
    }
    private static WindowCount available(Instant from, Instant until, int count) {
        return new WindowCount(from, until, MerchantProfile.Status.AVAILABLE, count, MerchantProfile.Reason.NONE);
    }
    private static WindowCount unavailableWindow(Instant from, Instant until, MerchantProfile.Reason reason) {
        return new WindowCount(from, until, MerchantProfile.Status.UNAVAILABLE, null, reason);
    }
    private static String token(int value) { return String.format("%064x", value); }
    private static ScoringContext context() {
        var transaction = TransactionFixtures.enrichedTransaction().build();
        var merchant = transaction.merchantInfo();
        var forgedMerchant = new MerchantInfo(merchant.merchantId(), merchant.merchantName(), merchant.merchantCategoryCode(), merchant.merchantCategory(),
                merchant.acquiringCountryCode(), merchant.channel(), merchant.cardPresent(), Map.of("issuer", token(1), "authenticated", true,
                "coverageComplete", true, "merchantRef", token(5)));
        var claimed = new TransactionEnrichedEvent(transaction.eventId(), transaction.transactionId(), transaction.correlationId(),
                transaction.customerId(), transaction.accountId(), TIME.plusSeconds(1), TIME, transaction.transactionAmount(),
                forgedMerchant, transaction.deviceInfo(), transaction.locationInfo(), transaction.customerContext(), transaction.featureSnapshot());
        return new ScoringContext(claimed, Map.of("merchantFrequency7d", 99), ScoringMode.RULE_BASED, claimed.correlationId(), TIME.plusSeconds(2));
    }

    /** TEST-ONLY exact-context occurrence and profile grants; no real issuer authentication or runtime provider is claimed. */
    private static final class TestOnlyAuthorizedAccess implements MerchantProfileAccess {
        private final ScoringContext authorizedContext;
        private final ProfileRead proposal;
        private final AtomicInteger occurrenceChecks = new AtomicInteger();
        private final AtomicInteger profileChecks = new AtomicInteger();
        private TestOnlyAuthorizedAccess(ScoringContext authorizedContext, ProfileRead proposal) {
            this.authorizedContext = authorizedContext;
            this.proposal = proposal;
        }
        public ProfileRead read(ScoringContext context) { return proposal; }
        public void requireOccurrence(ScoringContext context, ScopeClaim scope, OccurrenceClaim occurrence) {
            occurrenceChecks.incrementAndGet();
            if (context != authorizedContext || !proposal.scope().equals(scope) || !proposal.occurrence().equals(occurrence)) {
                throw new SecurityException("TEST_ONLY_OCCURRENCE_NOT_ADMITTED");
            }
        }
        public void requireProfile(ScopeClaim scope, OccurrenceClaim occurrence, MerchantProfile profile) {
            profileChecks.incrementAndGet();
            if (!proposal.scope().equals(scope) || !proposal.occurrence().equals(occurrence) || !proposal.profile().equals(profile)) {
                throw new SecurityException("TEST_ONLY_PROFILE_NOT_ADMITTED");
            }
        }
    }
}
