package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static com.frauddetection.common.events.source.SourceOriginProofs.Assertion.KNOWN_ATTESTED;
import static com.frauddetection.common.events.source.SourceOriginProofs.Assertion.PROVEN_LEGACY;
import static org.assertj.core.api.Assertions.assertThat;

class SourceOriginProofsTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final SourceOriginProofs PROOFS = new SourceOriginProofs(
            new SourceOriginProperties(true, "origin-key-1", Map.of("origin-key-1", KEY)));
    private static final Instant TIME = Instant.parse("2026-11-09T10:00:00Z");

    @Test void exactStageBoundLegacyAndAttestedProofsVerifyWithoutExposingEventIdentity() {
        var raw = raw();
        String legacy = PROOFS.signRaw(raw, PROVEN_LEGACY);
        String attested = PROOFS.signRaw(raw, KNOWN_ATTESTED);

        assertThat(PROOFS.verifyRaw(raw, legacy).assertion()).isEqualTo(PROVEN_LEGACY);
        assertThat(PROOFS.verifyRaw(raw, attested).assertion()).isEqualTo(KNOWN_ATTESTED);
        assertThat(legacy).doesNotContain(raw.transactionId(), raw.eventId(), "customer-private");
    }

    @Test void proofCannotBeReplayedAcrossStagesOrChangedEventContent() {
        var raw = raw();
        String token = PROOFS.signRaw(raw, PROVEN_LEGACY);
        var enriched = enriched();
        var changedRaw = new TransactionRawEvent(raw.eventId(), raw.transactionId(), raw.correlationId(), "another-customer",
                raw.accountId(), raw.paymentInstrumentId(), raw.createdAt(), raw.transactionTimestamp(), raw.transactionAmount(),
                raw.merchantInfo(), raw.deviceInfo(), raw.locationInfo(), raw.customerContext(), raw.transactionType(),
                raw.authorizationMethod(), raw.sourceSystem(), raw.traceId(), raw.attributes());

        assertThat(PROOFS.verifyRaw(changedRaw, token).status()).isEqualTo(SourceOriginProofs.Status.INVALID);
        assertThat(PROOFS.verifyEnriched(enriched, token).status()).isEqualTo(SourceOriginProofs.Status.INVALID);
        assertThat(PROOFS.verifyRaw(raw, "v1.invalid.L.invalid").status()).isEqualTo(SourceOriginProofs.Status.INVALID);
        assertThat(PROOFS.verifyRaw(raw, null).status()).isEqualTo(SourceOriginProofs.Status.ABSENT);
    }

    private static TransactionRawEvent raw() {
        return new TransactionRawEvent("raw-event-1", "txn-private", "corr-private", "customer-private", "account-private",
                "instrument-private", TIME, TIME, null, null, null, null, null, "PURCHASE", "CHIP", "caller-label", "trace-private", Map.of());
    }

    private static TransactionEnrichedEvent enriched() {
        return new TransactionEnrichedEvent("enriched-event-1", "txn-private", "corr-private", "customer-private", "account-private",
                TIME, TIME, null, null, null, null, null, Map.of("merchantFrequency7d", 0));
    }
}
