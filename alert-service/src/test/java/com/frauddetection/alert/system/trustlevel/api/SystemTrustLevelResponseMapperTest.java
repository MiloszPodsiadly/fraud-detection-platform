package com.frauddetection.alert.system.trustlevel.api;

import com.frauddetection.alert.system.trustlevel.application.SystemTrustLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemTrustLevelResponseMapperTest {

    @Test
    void preservesEveryApplicationFieldAtTheHttpBoundary() {
        SystemTrustLevel trustLevel = new SystemTrustLevel(
                "guarantee",
                "bank-profile",
                true,
                false,
                true,
                "anchor-strength",
                "coverage",
                "witness",
                "signature-policy",
                1,
                2,
                3,
                4L,
                5L,
                6L,
                7L,
                8L,
                9L,
                10L,
                11L,
                12L,
                13L,
                14L,
                15L,
                16L,
                17L,
                18L,
                19L,
                20L,
                21L,
                22L,
                23L,
                24L,
                25L,
                26L,
                27L,
                "reason",
                "transaction-mode",
                "transaction-capability",
                "outbox-delivery",
                "evidence-confirmation",
                28L,
                29L,
                30L,
                31L,
                List.of("incident-type"),
                "incident-health"
        );

        SystemTrustLevelResponse response = new SystemTrustLevelResponseMapper().map(trustLevel);

        assertThat(response)
                .usingRecursiveComparison()
                .isEqualTo(trustLevel);
    }
}
