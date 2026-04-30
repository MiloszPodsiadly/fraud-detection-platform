package com.frauddetection.alert.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemTrustLevelControllerTest {

    @Test
    void shouldExposeFailClosedSignedExternalTrustLevel() {
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                true,
                true,
                true,
                true
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("FDP24_FAIL_CLOSED");
        assertThat(response.publicationEnabled()).isTrue();
        assertThat(response.publicationRequired()).isTrue();
        assertThat(response.failClosed()).isTrue();
        assertThat(response.externalAnchorStrength()).isEqualTo("SIGNED_EXTERNAL");
    }

    @Test
    void shouldNotMarketBestEffortAsFdp24FailClosed() {
        SystemTrustLevelController controller = new SystemTrustLevelController(
                true,
                false,
                false,
                false,
                false
        );

        SystemTrustLevelResponse response = controller.trustLevel();

        assertThat(response.guaranteeLevel()).isEqualTo("BEST_EFFORT");
        assertThat(response.externalAnchorStrength()).isEqualTo("UNSIGNED_EXTERNAL");
    }
}
