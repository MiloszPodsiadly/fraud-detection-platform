package com.frauddetection.alert.service;

import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueCursorSecretStartupGuard;
import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueCursorSecretProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void prodAndBankProfilesWithoutCursorSigningSecretShouldFailStartup() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod", "app.fraud-cases.work-queue.sla=PT8H")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("spring.profiles.active=bank", "app.fraud-cases.work-queue.sla=PT8H")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodAndBankProfilesWithBlankCursorSigningSecretShouldFailStartup() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret="
                )
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=bank",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret="
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodAndBankProfilesShouldRejectKnownLocalCursorSigningSecrets() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=local-dev-work-queue-cursor-secret-change-me"
                )
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=bank",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=local-test-work-queue-cursor-signing-secret"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void prodAndBankProfilesWithExplicitCursorSigningSecretShouldStart() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=prod-explicit-cursor-signing-secret"
                )
                .run(context -> assertThat(context).hasNotFailed());
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=bank",
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=bank-explicit-cursor-signing-secret"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FraudCaseWorkQueueProperties.class)
    @Import(FraudCaseWorkQueueCursorSecretStartupGuard.class)
    static class PropertiesConfig {
    }
}
