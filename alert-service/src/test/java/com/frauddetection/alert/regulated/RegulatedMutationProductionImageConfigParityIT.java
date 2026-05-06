package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("config-parity")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageConfigParityIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void productionImageStartsWithSafeRegulatedMutationDefaultsAndNoChaosProfile() {
        chaosHarness.startAlertService("config-parity", List.of(
                "--app.regulated-mutations.transaction-mode=OFF",
                "--app.regulated-mutation.lease-duration=PT30S",
                "--app.regulated-mutations.checkpoint-renewal.extension=PT10S",
                "--app.regulated-mutations.lease-renewal.max-renewal-count=3"
        ));

        assertThat(chaosHarness.imageName()).contains("alert-service");
        assertThat(chaosHarness.servicePort()).isPositive();
        assertThat(chaosHarness.lastEffectiveArgs())
                .contains(
                        "--spring.profiles.active=test",
                        "--app.security.demo-auth.enabled=true",
                        "--app.outbox.publisher.enabled=false",
                        "--app.evidence-confirmation.enabled=false",
                        "--app.regulated-mutation.recovery.scheduler.enabled=false",
                        "--app.regulated-mutations.transaction-mode=OFF",
                        "--app.regulated-mutation.lease-duration=PT30S",
                        "--app.regulated-mutations.checkpoint-renewal.extension=PT10S",
                        "--app.regulated-mutations.lease-renewal.max-renewal-count=3"
                )
                .noneMatch(argument -> argument.contains("fdp36-live-in-flight"))
                .noneMatch(argument -> argument.contains("production"));
    }
}
