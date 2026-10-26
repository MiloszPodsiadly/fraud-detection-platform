package com.frauddetection.enricher.device;

import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.enricher.device.DeviceObservationStore.Observation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceObservationStoreBoundaryTest {
    private static final Instant TIME = Instant.parse("2026-10-26T10:00:00Z");
    private static final String TOKEN = "a".repeat(64);

    @Test
    void clientAssertedDeviceCannotBeAdmittedAsVerifiedObservation() {
        assertThatThrownBy(() -> observation(TOKEN, TOKEN,
                DeviceRiskEvidence.SourceQuality.CLIENT_ASSERTED))
                .hasMessage("DEVICE_STORE_UNTRUSTED_INPUT");
    }

    @Test
    void syntheticDeviceCannotBeAdmittedAsVerifiedObservation() {
        assertThatThrownBy(() -> observation(TOKEN, TOKEN,
                DeviceRiskEvidence.SourceQuality.SYNTHETIC))
                .hasMessage("DEVICE_STORE_UNTRUSTED_INPUT");
    }

    @Test
    void rawClientIdentifierAndMissingInstitutionScopeAreRejected() {
        assertThatThrownBy(() -> observation(TOKEN, "browser-device-123",
                DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION))
                .hasMessage("DEVICE_STORE_TOKEN");
        assertThatThrownBy(() -> observation(null, TOKEN,
                DeviceRiskEvidence.SourceQuality.AUTHENTICATED_INSTITUTION))
                .hasMessage("DEVICE_STORE_TOKEN");
    }

    private static Observation observation(String institution, String device,
            DeviceRiskEvidence.SourceQuality quality) {
        return new Observation(institution, "BANK", TOKEN, TOKEN, TOKEN, device,
                1, 1, TIME, TIME, TOKEN, quality);
    }
}
