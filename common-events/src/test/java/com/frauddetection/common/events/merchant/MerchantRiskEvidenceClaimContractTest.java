package com.frauddetection.common.events.merchant;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.CoverageClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.HistoryWindowClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.OccurrenceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Reason;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.ScopeClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Signal;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SignalFact;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SourceAssertion;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.SourceClaim;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim.Status;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantRiskEvidenceClaimContractTest {
    private static final Instant TIME = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void structuralRoundTripRetainsClaimsAndNeverGrantsAuthority() {
        MerchantRiskEvidenceClaim claim = clientClaim();
        byte[] json = MerchantRiskEvidenceClaimJson.encode(claim);
        MerchantRiskEvidenceClaim decoded = MerchantRiskEvidenceClaimJson.decode(json);

        assertThat(decoded).isEqualTo(claim);
        assertThat(MerchantRiskEvidenceClaimJson.encode(decoded)).isEqualTo(json);
        assertThat(decoded.canonicalBytes()).isEqualTo(claim.canonicalBytes());
        assertThat(decoded.signals()).allSatisfy(fact -> {
            assertThat(fact.status()).isEqualTo(Status.UNTRUSTED);
            assertThat(fact.reason()).isEqualTo(Reason.CLIENT_ASSERTION);
        });
        assertThat(SourceAssertion.values()).extracting(Enum::name).doesNotContain("VERIFIED", "AUTHENTICATED_INSTITUTION");
        assertThat(Status.values()).extracting(Enum::name).doesNotContain("AVAILABLE");
        assertThat(CoverageClaim.values()).extracting(Enum::name).doesNotContain("COMPLETE_WINDOW");
    }

    @Test
    void matchingSelfAssertedBindingIsNotAuthenticationAndOtherScopesCannotMatch() {
        MerchantRiskEvidenceClaim claim = clientClaim();
        claim.requireClaimedBinding(scope(), occurrence());
        for (ScopeClaim substituted : List.of(
                new ScopeClaim(token('9'), token('b'), token('c'), token('d'), token('e'), 1),
                new ScopeClaim(token('a'), token('9'), token('c'), token('d'), token('e'), 1),
                new ScopeClaim(token('a'), token('b'), token('9'), token('d'), token('e'), 1),
                new ScopeClaim(token('a'), token('b'), token('c'), token('9'), token('e'), 1),
                new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('9'), 1),
                new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), 2))) {
            assertThatThrownBy(() -> claim.requireClaimedBinding(substituted, occurrence()))
                    .hasMessage("MERCHANT_CLAIM_BINDING_MISMATCH").hasNoCause();
        }
        assertThat(claim.signals()).allMatch(fact -> fact.status() == Status.UNTRUSTED);
    }

    @Test
    void changedEventTransactionOccurrenceSequenceTimeOrCommitmentCannotMatch() {
        for (OccurrenceClaim substituted : List.of(
                new OccurrenceClaim(token('9'), token('1'), token('2'), 1, TIME, token('3')),
                new OccurrenceClaim(token('f'), token('9'), token('2'), 1, TIME, token('3')),
                new OccurrenceClaim(token('f'), token('1'), token('9'), 1, TIME, token('3')),
                new OccurrenceClaim(token('f'), token('1'), token('2'), 2, TIME, token('3')),
                new OccurrenceClaim(token('f'), token('1'), token('2'), 1, TIME.minusSeconds(1), token('3')),
                new OccurrenceClaim(token('f'), token('1'), token('2'), 1, TIME, token('9')))) {
            assertThatThrownBy(() -> clientClaim().requireClaimedBinding(scope(), substituted))
                    .hasMessage("MERCHANT_CLAIM_BINDING_MISMATCH");
        }
    }

    @Test
    void syntheticAndUnspecifiedSourcesHaveOnlyExplicitNonInformativeDispositions() {
        MerchantRiskEvidenceClaim synthetic = claim(new SourceClaim(SourceAssertion.SYNTHETIC, TIME),
                signals(Status.UNTRUSTED, Reason.SYNTHETIC_INPUT));
        assertThat(synthetic.signals()).allMatch(fact -> fact.status() == Status.UNTRUSTED);
        assertThat(claim(new SourceClaim(SourceAssertion.UNSPECIFIED, TIME),
                signals(Status.UNAVAILABLE, Reason.NOT_ADMITTED)).signals())
                .allMatch(fact -> fact.status() == Status.UNAVAILABLE);
        assertThat(claim(new SourceClaim(SourceAssertion.UNSPECIFIED, TIME),
                signals(Status.UNSUPPORTED, Reason.METHODOLOGY_MISSING)).signals())
                .allMatch(fact -> fact.status() == Status.UNSUPPORTED);
        assertThatThrownBy(() -> claim(new SourceClaim(SourceAssertion.SYNTHETIC, TIME),
                signals(Status.UNAVAILABLE, Reason.NOT_ADMITTED)))
                .hasMessage("MERCHANT_CLAIM_SOURCE_FACT_CONFLICT");
        assertThatThrownBy(() -> claim(new SourceClaim(SourceAssertion.CLIENT_ASSERTED, TIME),
                signals(Status.UNTRUSTED, Reason.SYNTHETIC_INPUT)))
                .hasMessage("MERCHANT_CLAIM_SOURCE_FACT_CONFLICT");
        assertThatThrownBy(() -> claim(new SourceClaim(SourceAssertion.UNSPECIFIED, TIME),
                signals(Status.UNTRUSTED, Reason.CLIENT_ASSERTION)))
                .hasMessage("MERCHANT_CLAIM_SOURCE_FACT_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "raw-merchant-id", " abc", "A", "g", "aa"})
    void malformedReferencesAreRejectedWithoutEchoingInput(String badReference) {
        assertThatThrownBy(() -> new ScopeClaim(badReference, token('b'), token('c'), token('d'), token('e'), 1))
                .hasMessage("MERCHANT_CLAIM_REFERENCE").hasNoCause();
    }

    @Test
    void versionSequenceAndRequiredFieldsHaveStrictBounds() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        for (int version : new int[] {0, 2, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new MerchantRiskEvidenceClaim(version, scope(), occurrence(), valid.source(),
                    history(), valid.signals())).hasMessage("MERCHANT_CLAIM_VERSION");
        }
        for (int version : new int[] {0, -1, 65_536}) {
            assertThatThrownBy(() -> new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), version))
                    .hasMessage("MERCHANT_CLAIM_IDENTITY_VERSION");
        }
        assertThatThrownBy(() -> new ScopeClaim(null, token('b'), token('c'), token('d'), token('e'), 1))
                .hasMessage("MERCHANT_CLAIM_REFERENCE");
        assertThatThrownBy(() -> new OccurrenceClaim(token('f'), token('1'), token('2'), 0, TIME, token('3')))
                .hasMessage("MERCHANT_CLAIM_SOURCE_SEQUENCE");
        assertThatThrownBy(() -> new MerchantRiskEvidenceClaim(1, null, occurrence(), valid.source(), history(), valid.signals()))
                .hasMessage("MERCHANT_CLAIM_REQUIRED_FIELD");
        assertThatThrownBy(() -> new SourceClaim(null, TIME)).hasMessage("MERCHANT_CLAIM_SOURCE_ASSERTION");
        assertThatThrownBy(() -> new SourceClaim(SourceAssertion.UNSPECIFIED, null)).hasMessage("MERCHANT_CLAIM_TIME");
    }

    @Test
    void claimedSampleDoesNotBecomeCompleteEvenWhenItsIntervalSpansTheWindow() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        HistoryWindowClaim sample = new HistoryWindowClaim(TIME.minusSeconds(60), TIME,
                TIME.minusSeconds(60), TIME, CoverageClaim.OBSERVED_SAMPLE);
        MerchantRiskEvidenceClaim sampled = new MerchantRiskEvidenceClaim(1, scope(), occurrence(), valid.source(),
                sample, valid.signals());
        assertThat(sampled.history().coverage()).isEqualTo(CoverageClaim.OBSERVED_SAMPLE);
        assertThat(sampled.signals()).allMatch(fact -> fact.status() == Status.UNTRUSTED);
    }

    @Test
    void temporalContradictionsAndFutureKnowledgeAreRejected() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        for (Instant cutoff : List.of(TIME.minusSeconds(1), TIME.plusSeconds(1))) {
            assertThatThrownBy(() -> new MerchantRiskEvidenceClaim(1, scope(), occurrence(), valid.source(),
                    new HistoryWindowClaim(cutoff.minusSeconds(60), cutoff, null, null, CoverageClaim.INCOMPLETE),
                    valid.signals())).hasMessage("MERCHANT_CLAIM_OCCURRENCE_CUTOFF");
        }
        assertThatThrownBy(() -> claim(new SourceClaim(SourceAssertion.CLIENT_ASSERTED, TIME.plusNanos(1)), valid.signals()))
                .hasMessage("MERCHANT_CLAIM_SOURCE_TIME");
        assertThatThrownBy(() -> new HistoryWindowClaim(TIME, TIME, null, null, CoverageClaim.INCOMPLETE))
                .hasMessage("MERCHANT_CLAIM_WINDOW");
        assertThatThrownBy(() -> new HistoryWindowClaim(TIME.minus(MerchantRiskEvidenceClaim.MAX_WINDOW).minusNanos(1),
                TIME, null, null, CoverageClaim.INCOMPLETE)).hasMessage("MERCHANT_CLAIM_WINDOW");
        assertThatThrownBy(() -> new HistoryWindowClaim(TIME.minusSeconds(60), TIME,
                TIME.minusSeconds(60), TIME.plusNanos(1), CoverageClaim.OBSERVED_SAMPLE))
                .hasMessage("MERCHANT_CLAIM_COVERAGE_WINDOW");
        assertThatThrownBy(() -> new HistoryWindowClaim(TIME.minusSeconds(60), TIME,
                TIME.minusSeconds(61), TIME, CoverageClaim.OBSERVED_SAMPLE))
                .hasMessage("MERCHANT_CLAIM_COVERAGE_WINDOW");
        assertThatThrownBy(() -> new HistoryWindowClaim(TIME.minusSeconds(60), TIME,
                TIME.minusSeconds(60), TIME, CoverageClaim.INCOMPLETE)).hasMessage("MERCHANT_CLAIM_COVERAGE_WINDOW");
        assertThatThrownBy(() -> new SourceClaim(SourceAssertion.CLIENT_ASSERTED, Instant.parse("2100-01-01T00:00:00Z")))
                .hasMessage("MERCHANT_CLAIM_TIME");
    }

    @Test
    void stateAndReasonContradictionsCannotCreateFacts() {
        assertThatThrownBy(() -> new SignalFact(Signal.MERCHANT_FRAUD_RATE, Status.UNAVAILABLE, Reason.CLIENT_ASSERTION))
                .hasMessage("MERCHANT_CLAIM_STATE_REASON");
        assertThatThrownBy(() -> new SignalFact(Signal.MERCHANT_ACTIVITY_SPIKE, Status.UNSUPPORTED, Reason.NO_SOURCE))
                .hasMessage("MERCHANT_CLAIM_STATE_REASON");
        assertThatThrownBy(() -> new SignalFact(Signal.MERCHANT_CATEGORY_CONTEXT, Status.INVALID, Reason.NOT_ADMITTED))
                .hasMessage("MERCHANT_CLAIM_STATE_REASON");
    }

    @Test
    void signalSetIsBoundedUniqueImmutableAndCanonicallyOrdered() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        ArrayList<SignalFact> input = new ArrayList<>(valid.signals());
        Collections.reverse(input);
        MerchantRiskEvidenceClaim copied = claim(valid.source(), input);
        input.clear();
        assertThat(copied.signals()).isEqualTo(valid.signals());
        assertThat(copied.canonicalBytes()).isEqualTo(valid.canonicalBytes());
        assertThatThrownBy(() -> copied.signals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> claim(valid.source(), List.of())).hasMessage("MERCHANT_CLAIM_SIGNALS");
        assertThatThrownBy(() -> claim(valid.source(), Collections.nCopies(5, valid.signals().getFirst())))
                .hasMessage("MERCHANT_CLAIM_SIGNALS");
        assertThatThrownBy(() -> claim(valid.source(), Arrays.asList(null, null, null, null, null)))
                .hasMessage("MERCHANT_CLAIM_SIGNALS");
        assertThatThrownBy(() -> claim(valid.source(), Collections.nCopies(6, valid.signals().getFirst())))
                .hasMessage("MERCHANT_CLAIM_SIGNALS");
    }

    @Test
    void canonicalBytesAreBoundedFreshAndCommitToEveryScopeAndOccurrenceField() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        byte[] bytes = valid.canonicalBytes();
        assertThat(bytes).hasSizeLessThanOrEqualTo(MerchantRiskEvidenceClaim.MAX_CANONICAL_BYTES);
        bytes[0] ^= 1;
        assertThat(valid.canonicalBytes()).isNotEqualTo(bytes);
        MerchantRiskEvidenceClaim changed = new MerchantRiskEvidenceClaim(1, scope(),
                new OccurrenceClaim(token('f'), token('1'), token('2'), 1, TIME, token('9')),
                valid.source(), history(), valid.signals());
        assertThat(changed.canonicalBytes()).isNotEqualTo(valid.canonicalBytes());
        MerchantRiskEvidenceClaim changedScope = new MerchantRiskEvidenceClaim(1,
                new ScopeClaim(token('a'), token('9'), token('c'), token('d'), token('e'), 1),
                occurrence(), valid.source(), history(), valid.signals());
        assertThat(changedScope.canonicalBytes()).isNotEqualTo(valid.canonicalBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"verified\":true,", "\"admission\":\"VERIFIED\",", "\"keyVersion\":1,",
            "\"sourceSystem\":\"BANK\",", "\"metadata\":{},", "\"fraudCount\":90,\"populationCount\":100,",
            "\"reviewedSampleFraudRate\":0.9,", "\"labelsMatureAt\":\"2028-01-01T00:00:00Z\",", "\"categoryRisk\":\"HIGH\","
    })
    void extraTopLevelClaimsCannotDeclareAuthority(String extraField) {
        assertInvalid(json().replaceFirst("\\{", "{" + extraField));
    }

    @Test
    void everyNestedObjectRejectsUnknownFieldsIncludingSensitiveValues() {
        for (String field : List.of("scope", "occurrence", "source", "history")) {
            assertInvalid(json().replace("\"" + field + "\":{", "\"" + field + "\":{\"secret-raw-id\":\"private\","));
        }
        assertInvalid(json().replace("\"signals\":[{", "\"signals\":[{\"value\":0,"));
        assertInvalid(json().replace("\"signals\":[{", "\"signals\":[{\"riskLevel\":\"LOW\","));
        assertInvalid(json().replace("\"signals\":[{", "\"signals\":[{\"predicate\":false,"));
    }

    @Test
    void fakeVerifiedSourceCompleteCoverageAndAvailableFactsAreRejected() {
        assertInvalid(json().replace("CLIENT_ASSERTED", "AUTHENTICATED_INSTITUTION"));
        assertInvalid(json().replace("INCOMPLETE", "COMPLETE_WINDOW"));
        assertInvalid(json().replace("UNTRUSTED", "AVAILABLE"));
        assertInvalid(json().replace("CLIENT_ASSERTION", "NONE"));
    }

    @Test
    void duplicateDecodedKeysAtEveryDepthAreRejectedBeforeBinding() {
        assertInvalid(json().replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"));
        assertInvalid(json().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schema\\u0056ersion\":1"));
        assertInvalid(json().replace("\"scope\":{", "\"scope\":{},\"scope\":{"));
        assertInvalid(json().replace("\"identityVersion\":1", "\"identityVersion\":1,\"identityVersion\":1"));
        assertInvalid(json().replace("\"sourceSequence\":1", "\"sourceSequence\":1,\"sourceSequence\":1"));
        assertInvalid(json().replace("\"assertion\":", "\"assertion\":\"SYNTHETIC\",\"assertion\":"));
        assertInvalid(json().replace("\"coverage\":", "\"coverage\":\"INCOMPLETE\",\"coverage\":"));
        assertInvalid(json().replace("\"status\":", "\"status\":\"UNTRUSTED\",\"status\":"));
    }

    @Test
    void malformedMissingCoercedAndOutOfRangeJsonValuesAreRejected() {
        assertInvalid(json().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertInvalid(json().replace("\"schemaVersion\":1,", ""));
        assertInvalid(json().replace("\"schemaVersion\":1", "\"schemaVersion\":null"));
        assertInvalid(json().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""));
        assertInvalid(json().replace("\"identityVersion\":1", "\"identityVersion\":1.5"));
        assertInvalid(json().replace("\"sourceSequence\":1", "\"sourceSequence\":9223372036854775808"));
        assertInvalid(json().replace(token('e'), "raw-merchant@example.invalid"));
        assertInvalid(json().replace(token('e'), "e".repeat(65)));
        assertInvalid(json().replace(token('e'), "e".repeat(129)));
        assertInvalid(json().replace("\"source\":{", "\"source\":null,\"unexpected\":{"));
        assertInvalid("null");
        assertInvalid("[]");
        assertInvalid("{");
        assertInvalid(json() + " {}");
        assertInvalid("{\"scope\":" + "[".repeat(9) + "0" + "]".repeat(9) + "}");
    }

    @Test
    void numericOrdinalsAndNumericDatesCannotChangeTheWireShape() {
        assertInvalid(json().replace("\"assertion\":\"CLIENT_ASSERTED\"", "\"assertion\":1"));
        assertInvalid(json().replace("\"status\":\"UNTRUSTED\"", "\"status\":1"));
        assertInvalid(json().replace("\"coverage\":\"INCOMPLETE\"", "\"coverage\":1"));
        for (String field : List.of("eventTime", "observedAt", "asOf")) {
            assertInvalid(json().replace("\"" + field + "\":\"" + TIME + "\"",
                    "\"" + field + "\":" + TIME.getEpochSecond()));
        }
        assertInvalid(json().replace("\"windowStart\":\"" + TIME.minusSeconds(60) + "\"",
                "\"windowStart\":" + TIME.minusSeconds(60).getEpochSecond()));
        MerchantRiskEvidenceClaim valid = clientClaim();
        String sample = new String(MerchantRiskEvidenceClaimJson.encode(new MerchantRiskEvidenceClaim(1, scope(),
                occurrence(), valid.source(), new HistoryWindowClaim(TIME.minusSeconds(60), TIME,
                TIME.minusSeconds(60), TIME, CoverageClaim.OBSERVED_SAMPLE), valid.signals())), StandardCharsets.UTF_8);
        assertInvalid(sample.replace("\"coveredFrom\":\"" + TIME.minusSeconds(60) + "\"",
                "\"coveredFrom\":" + TIME.minusSeconds(60).getEpochSecond()));
        assertInvalid(sample.replace("\"coveredThrough\":\"" + TIME + "\"",
                "\"coveredThrough\":" + TIME.getEpochSecond()));
    }

    @Test
    void utf8AndExactByteCeilingAreEnforcedWithRedactedErrors() {
        byte[] encoded = MerchantRiskEvidenceClaimJson.encode(clientClaim());
        assertThat(encoded).hasSizeLessThanOrEqualTo(MerchantRiskEvidenceClaimJson.MAX_SERIALIZED_BYTES);
        String atLimit = json() + " ".repeat(MerchantRiskEvidenceClaimJson.MAX_SERIALIZED_BYTES - encoded.length);
        assertThat(MerchantRiskEvidenceClaimJson.decode(utf8(atLimit))).isEqualTo(clientClaim());
        assertInvalid(atLimit + " ");
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.decode(new byte[] {'{', (byte) 0xc3, '}'}))
                .hasMessage("MERCHANT_CLAIM_JSON_INVALID").hasNoCause();
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.decode(json().getBytes(StandardCharsets.UTF_16LE)))
                .hasMessage("MERCHANT_CLAIM_JSON_INVALID").hasNoCause();
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.decode(null)).hasMessage("MERCHANT_CLAIM_JSON_INVALID");
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.decode(new byte[0])).hasMessage("MERCHANT_CLAIM_JSON_INVALID");
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.encode(null)).hasMessage("MERCHANT_CLAIM_JSON_INVALID");
    }

    @Test
    void allPrintableDomainObjectsAreRedactedAndPublicEventsRemainUnchanged() {
        MerchantRiskEvidenceClaim valid = clientClaim();
        for (Object value : List.of(valid, valid.scope(), valid.occurrence(), valid.source(), valid.history(), valid.signals().getFirst())) {
            assertThat(value.toString()).contains("redacted").doesNotContain(token('a'), token('e'), token('3'), TIME.toString());
        }
        for (Class<?> eventType : List.of(TransactionRawEvent.class, TransactionEnrichedEvent.class, TransactionScoredEvent.class)) {
            assertThat(Arrays.stream(eventType.getRecordComponents()).map(RecordComponent::getType))
                    .doesNotContain(MerchantRiskEvidenceClaim.class);
        }
        assertThat(FraudEngineIdentityContract.ENGINE_INTELLIGENCE_CONTRACT_VERSION).isEqualTo(2);
        assertThat(FraudEngineIdentityContract.engineOrder())
                .containsExactly("rules.primary", "ml.python.primary", "velocity.primary", "device.primary");
    }

    private static MerchantRiskEvidenceClaim clientClaim() {
        return claim(new SourceClaim(SourceAssertion.CLIENT_ASSERTED, TIME), signals(Status.UNTRUSTED, Reason.CLIENT_ASSERTION));
    }

    private static MerchantRiskEvidenceClaim claim(SourceClaim source, List<SignalFact> signals) {
        return new MerchantRiskEvidenceClaim(1, scope(), occurrence(), source, history(), signals);
    }

    private static ScopeClaim scope() {
        return new ScopeClaim(token('a'), token('b'), token('c'), token('d'), token('e'), 1);
    }

    private static OccurrenceClaim occurrence() {
        return new OccurrenceClaim(token('f'), token('1'), token('2'), 1, TIME, token('3'));
    }

    private static HistoryWindowClaim history() {
        return new HistoryWindowClaim(TIME.minusSeconds(60), TIME, null, null, CoverageClaim.INCOMPLETE);
    }

    private static List<SignalFact> signals(Status status, Reason reason) {
        return Arrays.stream(Signal.values()).map(signal -> new SignalFact(signal, status, reason)).toList();
    }

    private static String token(char character) { return String.valueOf(character).repeat(64); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String json() { return new String(MerchantRiskEvidenceClaimJson.encode(clientClaim()), StandardCharsets.UTF_8); }

    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> MerchantRiskEvidenceClaimJson.decode(utf8(json)))
                .hasMessage("MERCHANT_CLAIM_JSON_INVALID").hasNoCause();
    }
}
