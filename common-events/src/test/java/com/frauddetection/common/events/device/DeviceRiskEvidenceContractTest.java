package com.frauddetection.common.events.device;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.device.DeviceRiskEvidence.CountryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.HistoryFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.NetworkFact;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Reason;
import com.frauddetection.common.events.device.DeviceRiskEvidence.Source;
import com.frauddetection.common.events.device.DeviceRiskEvidence.SourceQuality;
import com.frauddetection.common.events.device.DeviceRiskEvidence.State;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceRiskEvidenceContractTest {
    private static final String OCCURRENCE = "a".repeat(64);
    private static final String INSTITUTION = "b".repeat(64);
    private static final String CUSTOMER = "c".repeat(64);
    private static final String DEVICE = "d".repeat(64);
    private static final String SOURCE_EVENT = "e".repeat(64);
    private static final Instant TIME = Instant.parse("2026-10-26T10:05:00Z");
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void roundTripRetainsCanonicalOccurrenceAndOnlyPseudonymousIdentity() {
        DeviceRiskEvidence evidence = verified();
        String json = mapper.writeValueAsString(evidence);
        DeviceRiskEvidence decoded = mapper.readValue(json, DeviceRiskEvidence.class);

        assertThat(decoded).isEqualTo(evidence);
        assertThat(decoded.canonicalBytes()).isEqualTo(evidence.canonicalBytes());
        assertThat(mapper.writeValueAsString(decoded)).isEqualTo(json);
        assertThat(json).doesNotContain("raw-device-id", "192.0.2.1", "trustedDevice", "sourceSystem");
        assertThat(evidence.toString()).isEqualTo("DeviceRiskEvidence[redacted]");
        assertThat(evidence.source().toString()).isEqualTo("Source[redacted]");
        assertThat(evidence.priorDevice().toString()).isEqualTo("HistoryFact[redacted]");
        assertThat(evidence.country().toString()).isEqualTo("CountryFact[redacted]");
        assertThat(evidence.network().toString()).isEqualTo("NetworkFact[redacted]");
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents())
                .map(RecordComponent::getName)).doesNotContain("deviceRiskEvidence");
    }

    @Test
    void versionTokensAndKeyVersionAreStrict() {
        assertThatThrownBy(() -> evidence(2, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_VERSION");
        assertThatThrownBy(() -> evidence(1, "raw-device-id", INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_OCCURRENCE_KEY");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 0,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_KEY_VERSION");
        assertThatThrownBy(() -> new Source(SourceQuality.AUTHENTICATED_INSTITUTION,
                "A".repeat(65), SOURCE_EVENT, TIME)).hasMessage("DEVICE_EVIDENCE_SOURCE_AUTHORITY");
        assertThatThrownBy(() -> new Source(SourceQuality.AUTHENTICATED_INSTITUTION,
                "BANK", "e".repeat(65), TIME)).hasMessage("DEVICE_EVIDENCE_SOURCE_EVENT_KEY");
    }

    @Test
    void missingAndCorruptJsonCannotCreateEvidence() {
        String json = mapper.writeValueAsString(verified());
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                DeviceRiskEvidence.class)).hasRootCauseMessage("DEVICE_EVIDENCE_VERSION");
        assertThatThrownBy(() -> mapper.readValue(json.replaceFirst("\"source\":\\{[^}]*},", ""),
                DeviceRiskEvidence.class)).hasRootCauseMessage("SOURCE_REQUIRED");
        assertThatThrownBy(() -> mapper.readValue(json.replace(SOURCE_EVENT, "raw-device-id"),
                DeviceRiskEvidence.class)).hasRootCauseMessage("DEVICE_EVIDENCE_SOURCE_EVENT_KEY");
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"schemaVersion\":1",
                "\"trustedDevice\":true,\"schemaVersion\":1"), DeviceRiskEvidence.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"network\":{",
                "\"network\":{\"trustedDevice\":true,"), DeviceRiskEvidence.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                null, TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("SOURCE_REQUIRED");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, null, available(0),
                available(0), noCountry(), noNetwork())).hasMessage("PRIOR_DEVICE_REQUIRED");
    }

    @Test
    void asOfWindowAndCoverageMustBeCoherent() {
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(31L * 24 * 3600), TIME, TIME, available(0),
                available(0), available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_WINDOW");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME.plusSeconds(1), TIME, available(0),
                available(0), available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_WINDOW");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME.minusSeconds(1), available(0),
                available(0), available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_INCOMPLETE_HISTORY");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                new Source(SourceQuality.AUTHENTICATED_INSTITUTION, "BANK", SOURCE_EVENT, TIME.plusSeconds(121)),
                TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_SOURCE_TIME");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                new Source(SourceQuality.AUTHENTICATED_INSTITUTION, "BANK", SOURCE_EVENT, TIME.minusSeconds(301)),
                TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_SOURCE_TIME");
    }

    @Test
    void sourceClaimsCannotPromoteClientOrSyntheticFlags() {
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                new Source(SourceQuality.CLIENT_ASSERTED, "CLIENT", SOURCE_EVENT, TIME),
                TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_UNTRUSTED_AVAILABLE");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                new Source(SourceQuality.SYNTHETIC, "SIMULATOR", SOURCE_EVENT, TIME),
                TIME, TIME.minusSeconds(60), TIME, null, unavailable(), unavailable(),
                unavailable(), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_SOURCE_FACT_CONFLICT");
        assertThat(clientUntrusted().priorDevice().state()).isEqualTo(State.UNTRUSTED);
    }

    @Test
    void countsReasonsAndUnavailableFieldsAreBounded() {
        assertThatThrownBy(() -> new HistoryFact(State.AVAILABLE, Reason.NONE, -1))
                .hasMessage("DEVICE_EVIDENCE_HISTORY_COUNT");
        assertThatThrownBy(() -> new HistoryFact(State.AVAILABLE, Reason.NONE, 25_001))
                .hasMessage("DEVICE_EVIDENCE_HISTORY_COUNT");
        assertThatThrownBy(() -> new HistoryFact(State.UNAVAILABLE, Reason.NONE, null))
                .hasMessage("DEVICE_EVIDENCE_STATE_REASON");
        assertThatThrownBy(() -> new HistoryFact(State.UNAVAILABLE, Reason.NO_SOURCE, 0))
                .hasMessage("DEVICE_EVIDENCE_HISTORY_WITHOUT_COVERAGE");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(2_001),
                available(0), noCountry(), noNetwork())).hasMessage("DEVICE_EVIDENCE_OTHER_CUSTOMERS_CAP");
    }

    @Test
    void countryRequiresFreshProviderEvidenceAndNeverUsesTransactionCountryMismatch() {
        assertThatThrownBy(() -> new CountryFact(State.AVAILABLE, Reason.NONE,
                "pl", "DE", "GEO", TIME)).hasMessage("DEVICE_EVIDENCE_COUNTRY_CODE");
        assertThatThrownBy(() -> new CountryFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING,
                "PL", null, null, null)).hasMessage("DEVICE_EVIDENCE_COUNTRY_WITHOUT_PROVIDER");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), new CountryFact(State.AVAILABLE, Reason.NONE,
                        "PL", "DE", "GEO", TIME.minusSeconds(601)), noNetwork()))
                .hasMessage("DEVICE_EVIDENCE_COUNTRY_STALE");
    }

    @Test
    void networkCannotBeAvailableFromCallerBooleanWithoutProvider() {
        assertThatThrownBy(() -> new NetworkFact(State.AVAILABLE, Reason.NONE,
                true, false, null, TIME)).hasMessage("DEVICE_EVIDENCE_NETWORK_PROVIDER");
        assertThatThrownBy(() -> new NetworkFact(State.UNTRUSTED, Reason.CLIENT_ASSERTION,
                true, false, null, null)).hasMessage("DEVICE_EVIDENCE_NETWORK_WITHOUT_PROVIDER");
        assertThatThrownBy(() -> evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 1,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(0),
                available(0), noCountry(), new NetworkFact(State.AVAILABLE, Reason.NONE,
                        true, false, "NETWORK", TIME.minusSeconds(601))))
                .hasMessage("DEVICE_EVIDENCE_NETWORK_STALE");
    }

    @Test
    void bindingRejectsWrongInstitutionAndOccurrenceWithoutEchoingTokens() {
        DeviceRiskEvidence evidence = verified();
        evidence.requireBinding(OCCURRENCE, INSTITUTION);
        assertThatThrownBy(() -> evidence.requireBinding("f".repeat(64), INSTITUTION))
                .hasMessage("DEVICE_EVIDENCE_BINDING_MISMATCH").hasMessageNotContaining(OCCURRENCE);
        assertThatThrownBy(() -> evidence.requireBinding(OCCURRENCE, "f".repeat(64)))
                .hasMessage("DEVICE_EVIDENCE_BINDING_MISMATCH").hasMessageNotContaining(INSTITUTION);
    }

    private static DeviceRiskEvidence verified() {
        return evidence(1, OCCURRENCE, INSTITUTION, CUSTOMER, DEVICE, 7,
                trusted(), TIME, TIME.minusSeconds(60), TIME, TIME, available(0), available(1),
                available(2), noCountry(), noNetwork());
    }

    private static DeviceRiskEvidence clientUntrusted() {
        HistoryFact untrusted = new HistoryFact(State.UNTRUSTED, Reason.CLIENT_ASSERTION, null);
        return evidence(1, OCCURRENCE, INSTITUTION, null, null, 7,
                new Source(SourceQuality.CLIENT_ASSERTED, "CLIENT", SOURCE_EVENT, TIME),
                TIME, TIME.minusSeconds(60), TIME, null, untrusted, untrusted, untrusted,
                new CountryFact(State.UNTRUSTED, Reason.CLIENT_ASSERTION, null, null, null, null),
                new NetworkFact(State.UNTRUSTED, Reason.CLIENT_ASSERTION, null, null, null, null));
    }

    private static DeviceRiskEvidence evidence(int version, String occurrence, String institution,
            String customer, String device, int keyVersion, Source source, Instant eventTime,
            Instant windowStart, Instant asOf, Instant coverage, HistoryFact prior,
            HistoryFact customers, HistoryFact switches, CountryFact country, NetworkFact network) {
        return new DeviceRiskEvidence(version, occurrence, institution, customer, device, keyVersion,
                source, eventTime, windowStart, asOf, coverage, prior, customers, switches, country, network);
    }

    private static Source trusted() {
        return new Source(SourceQuality.AUTHENTICATED_INSTITUTION, "BANK", SOURCE_EVENT, TIME);
    }

    private static HistoryFact available(int count) {
        return new HistoryFact(State.AVAILABLE, Reason.NONE, count);
    }

    private static HistoryFact unavailable() {
        return new HistoryFact(State.UNAVAILABLE, Reason.NO_SOURCE, null);
    }

    private static CountryFact noCountry() {
        return new CountryFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null);
    }

    private static NetworkFact noNetwork() {
        return new NetworkFact(State.UNAVAILABLE, Reason.PROVIDER_MISSING, null, null, null, null);
    }
}
