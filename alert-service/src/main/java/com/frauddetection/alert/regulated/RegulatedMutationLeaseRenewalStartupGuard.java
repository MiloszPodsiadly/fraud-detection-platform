package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

@Component
public class RegulatedMutationLeaseRenewalStartupGuard implements ApplicationRunner {

    private final Environment environment;
    private final boolean bankModeFailClosed;
    private final Duration leaseDuration;
    private final Duration maxSingleExtension;
    private final Duration maxTotalLeaseDuration;
    private final Duration maxAllowedTotalDuration;
    private final int maxRenewalCount;

    public RegulatedMutationLeaseRenewalStartupGuard(
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed,
            @Value("${app.regulated-mutation.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${app.regulated-mutations.lease-renewal.max-single-extension:PT30S}") Duration maxSingleExtension,
            @Value("${app.regulated-mutations.lease-renewal.max-total-lease-duration:PT2M}") Duration maxTotalLeaseDuration,
            @Value("${app.regulated-mutations.lease-renewal.max-allowed-total-duration:PT10M}") Duration maxAllowedTotalDuration,
            @Value("${app.regulated-mutations.lease-renewal.max-renewal-count:3}") int maxRenewalCount
    ) {
        this.environment = environment;
        this.bankModeFailClosed = bankModeFailClosed;
        this.leaseDuration = leaseDuration;
        this.maxSingleExtension = maxSingleExtension;
        this.maxTotalLeaseDuration = maxTotalLeaseDuration;
        this.maxAllowedTotalDuration = maxAllowedTotalDuration;
        this.maxRenewalCount = maxRenewalCount;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!prodLike()) {
            return;
        }
        require("app.regulated-mutation.lease-duration", "positive", positive(leaseDuration),
                "bank/prod regulated mutations require a positive base lease duration.");
        require("app.regulated-mutations.lease-renewal.max-single-extension", "positive", positive(maxSingleExtension),
                "bank/prod lease renewal single extension must be positive.");
        require("app.regulated-mutations.lease-renewal.max-total-lease-duration", "positive", positive(maxTotalLeaseDuration),
                "bank/prod lease renewal total duration must be positive.");
        require("app.regulated-mutations.lease-renewal.max-allowed-total-duration", "positive", positive(maxAllowedTotalDuration),
                "bank/prod lease renewal safe maximum must be positive.");
        require("app.regulated-mutations.lease-renewal.max-renewal-count", ">=1", maxRenewalCount >= 1,
                "bank/prod lease renewal count must not imply no durable renewal path.");
        require("app.regulated-mutations.lease-renewal.max-total-lease-duration", ">= app.regulated-mutation.lease-duration",
                maxTotalLeaseDuration.compareTo(leaseDuration) >= 0,
                "bank/prod total renewal budget must cover at least the base lease duration.");
        require("app.regulated-mutations.lease-renewal.max-single-extension", "<= max-total-lease-duration",
                maxSingleExtension.compareTo(maxTotalLeaseDuration) <= 0,
                "bank/prod single renewal extension must not exceed the total budget.");
        require("app.regulated-mutations.lease-renewal.max-total-lease-duration", "<= max-allowed-total-duration",
                maxTotalLeaseDuration.compareTo(maxAllowedTotalDuration) <= 0,
                "bank/prod total renewal budget must remain bounded.");
    }

    private boolean prodLike() {
        return bankModeFailClosed || Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging")
                        || profile.equals("bank"));
    }

    private boolean positive(Duration duration) {
        return duration != null && duration.isPositive();
    }

    private void require(String setting, String required, boolean valid, String reason) {
        if (!valid) {
            throw new IllegalStateException("FDP-33 bank/prod startup guard failed: setting="
                    + setting + "; required=" + required + "; reason=" + reason);
        }
    }
}
