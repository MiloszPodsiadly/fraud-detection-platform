package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.merchant.MerchantProfile;
import com.frauddetection.common.events.merchant.MerchantProfile.Reason;
import com.frauddetection.common.events.merchant.MerchantProfile.Status;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.merchant.MerchantObservationStore.AdmissionVerifier;
import com.frauddetection.enricher.merchant.MerchantObservationStore.CoverageSeal;
import com.frauddetection.enricher.merchant.MerchantObservationStore.HistoryRead;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Observation;
import com.frauddetection.enricher.merchant.MerchantObservationStore.Scope;
import com.frauddetection.enricher.merchant.MerchantProfileProjector.Windows;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("UNTRUSTED_TEST_ONLY")
class MerchantSourceOccurrenceVerticalContractTest {
    private static final Instant EVENT_TIME = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Windows WINDOWS = new Windows(WINDOW, WINDOW, 100);

    @Test
    void currentRawAndEnrichedEventsCannotAuthorizeMerchantHistory() {
        TransactionRawEvent raw = rawEvent();
        TransactionEnrichedEvent enriched = new TransactionEnrichedEventMapper()
                .toEvent(raw, new EnrichedTransactionFeatures(Map.of()));

        assertThat(raw.sourceSystem()).isEqualTo("CLIENT_ASSERTED_SOURCE");
        assertThat(enriched.eventId()).isNotEqualTo(raw.eventId());

        // These references and sequence only make a well-formed caller claim for the deny-path test.
        // The independent test authority has no grant for them; none is evidence from ingest.
        Scope claimedScope = new Scope(ref("claimed-issuer"), ref("missing-institution"), ref(raw.sourceSystem()),
                ref("claimed-epoch"), 1);
        Observation claimedOccurrence = new Observation(claimedScope, ref(raw.eventId()), ref(raw.transactionId()),
                ref(enriched.eventId()), ref(raw.merchantInfo().merchantId()), 1, raw.transactionTimestamp(),
                raw.createdAt(), ref(raw.toString()));
        CoverageSeal claimedCoverage = new CoverageSeal(claimedScope, 1, EVENT_TIME.minusSeconds(7_200), EVENT_TIME.plusSeconds(60));
        HistoryRead claimedRead = new HistoryRead(claimedOccurrence, claimedCoverage, EVENT_TIME.minusSeconds(7_200),
                EVENT_TIME, EVENT_TIME.plusSeconds(120), EVENT_TIME.minusSeconds(7_200), 0, List.of(), false);

        IndependentTestAuthority authority = new IndependentTestAuthority();
        MerchantProfile profile = new MerchantProfileProjector(WINDOWS, authority).project(claimedRead);

        assertThat(authority.checkedObservation).isEqualTo(claimedOccurrence);
        assertThat(profile.occurrence().scoringOccurrenceRef()).isEqualTo(ref(enriched.eventId()));
        assertThat(profile.recent().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(profile.recent().count()).isNull();
        assertThat(profile.recent().reason()).isEqualTo(Reason.NOT_ADMITTED);
        assertThat(profile.baseline().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(profile.baseline().count()).isNull();
        assertThat(profile.baseline().reason()).isEqualTo(Reason.NOT_ADMITTED);
    }

    private static TransactionRawEvent rawEvent() {
        return new TransactionRawEvent("raw-event-41", "transaction-41", "correlation-41", "customer-41", "account-41",
                "instrument-41", EVENT_TIME, EVENT_TIME, new Money(new BigDecimal("12.34"), "PLN"),
                new MerchantInfo("merchant-41", "Fixture merchant", "5411", "GROCERY", "PL", "ECOM", false, Map.of()),
                new DeviceInfo("device-41", "fingerprint-41", "192.0.2.41", "fixture-agent", "web", "fixture",
                        false, false, false, Map.of()),
                new LocationInfo("PL", "Mazowieckie", "Warsaw", "00-001", 52.23, 21.01, "Europe/Warsaw", false),
                new CustomerContext("customer-41", "account-41", "fixture", "example.test", 30, true, true,
                        "PL", "PLN", List.of(), Map.of()),
                "PURCHASE", "CHIP", "CLIENT_ASSERTED_SOURCE", "trace-41", Map.of());
    }

    private static String ref(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Independent fixture authority: only external test grants can admit; the current ingest path has none. */
    private static final class IndependentTestAuthority implements AdmissionVerifier {
        private final Set<Observation> sourceGrants = Set.of();
        private final Set<CoverageSeal> coverageGrants = Set.of();
        private final Set<Scope> readGrants = Set.of();
        private final Set<HistoryRead> completeReadGrants = Set.of();
        private Observation checkedObservation;

        @Override
        public void requireObservation(Observation observation) {
            checkedObservation = observation;
            if (!sourceGrants.contains(observation)) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }

        @Override
        public void requireCoverage(CoverageSeal seal) {
            if (!coverageGrants.contains(seal)) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }

        @Override
        public void requireRead(Scope scope) {
            if (!readGrants.contains(scope)) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }

        @Override
        public void requireHistoryRead(HistoryRead read) {
            if (!completeReadGrants.contains(read)) { throw new SecurityException("TEST_ONLY_NOT_ADMITTED"); }
        }
    }
}
