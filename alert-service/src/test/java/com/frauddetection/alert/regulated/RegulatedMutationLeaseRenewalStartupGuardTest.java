package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegulatedMutationLeaseRenewalStartupGuardTest {

    private final Environment environment = mock(Environment.class);

    @Test
    void bankModeRejectsZeroRenewalCount() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThatThrownBy(() -> guard(true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofSeconds(30), 0).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FDP-33 bank/prod startup guard failed")
                .hasMessageContaining("max-renewal-count");
    }

    @Test
    void productionProfileRejectsTotalBudgetBelowBaseLease() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> guard(false, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(20), Duration.ofMinutes(10), Duration.ofSeconds(10), 1).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-total-lease-duration")
                .hasMessageContaining("lease-duration");
    }

    @Test
    void stagingProfileRejectsSingleExtensionAboveTotalBudget() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        assertThatThrownBy(() -> guard(false, Duration.ofSeconds(30), Duration.ofSeconds(90),
                Duration.ofSeconds(60), Duration.ofMinutes(10), Duration.ofSeconds(30), 1).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-single-extension")
                .hasMessageContaining("max-total-lease-duration");
    }

    @Test
    void bankProfileRejectsTotalBudgetAboveAllowedMaximum() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"bank"});

        assertThatThrownBy(() -> guard(false, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(11), Duration.ofMinutes(10), Duration.ofSeconds(30), 1).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-total-lease-duration")
                .hasMessageContaining("max-allowed-total-duration");
    }

    @Test
    void localProfileDoesNotFailClosedOnCompatibilityConfiguration() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        assertThatCode(() -> guard(false, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, 0).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void bankModeAcceptsBoundedRenewalConfiguration() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThatCode(() -> guard(true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofSeconds(30), 3).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void bankModeRejectsNonPositiveCheckpointRenewalExtension() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThatThrownBy(() -> guard(true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ZERO, 3).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint-renewal.extension")
                .hasMessageContaining("positive");
    }

    @Test
    void bankModeRejectsCheckpointExtensionAboveSingleRenewalBudget() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThatThrownBy(() -> guard(true, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofSeconds(31), 3).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint-renewal.extension")
                .hasMessageContaining("max-single-extension");
    }

    @Test
    void bankModeRejectsCheckpointExtensionAboveTotalRenewalBudget() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        assertThatThrownBy(() -> guard(true, Duration.ofSeconds(10), Duration.ofSeconds(30),
                Duration.ofSeconds(20), Duration.ofMinutes(10), Duration.ofSeconds(21), 3).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint-renewal.extension")
                .hasMessageContaining("max-total-lease-duration");
    }

    private RegulatedMutationLeaseRenewalStartupGuard guard(
            boolean bankModeFailClosed,
            Duration leaseDuration,
            Duration maxSingleExtension,
            Duration maxTotalLeaseDuration,
            Duration maxAllowedTotalDuration,
            Duration checkpointRenewalExtension,
            int maxRenewalCount
    ) {
        return new RegulatedMutationLeaseRenewalStartupGuard(
                environment,
                bankModeFailClosed,
                leaseDuration,
                maxSingleExtension,
                maxTotalLeaseDuration,
                maxAllowedTotalDuration,
                checkpointRenewalExtension,
                maxRenewalCount
        );
    }
}
