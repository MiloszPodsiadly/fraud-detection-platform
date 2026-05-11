package com.frauddetection.alert.service;

import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseProdSlaConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void localDefaultShouldRemainVisibleForDevelopment() throws Exception {
        String applicationYaml = Files.readString(projectRoot().resolve("alert-service/src/main/resources/application.yml"));

        assertThat(applicationYaml).contains("sla: ${FRAUD_CASE_WORK_QUEUE_SLA:PT24H}");
        assertThat(applicationYaml).contains("cursor-signing-secret: ${FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET:");
    }

    @Test
    void prodAndBankProfilesMustRequireExplicitSlaWithoutDefault() throws Exception {
        String prodYaml = Files.readString(projectRoot().resolve("alert-service/src/main/resources/application-prod.yml"));
        String bankYaml = Files.readString(projectRoot().resolve("alert-service/src/main/resources/application-bank.yml"));

        assertThat(prodYaml).contains("sla: ${FRAUD_CASE_WORK_QUEUE_SLA}");
        assertThat(bankYaml).contains("sla: ${FRAUD_CASE_WORK_QUEUE_SLA}");
        assertThat(prodYaml).contains("cursor-signing-secret: ${FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET}");
        assertThat(bankYaml).contains("cursor-signing-secret: ${FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET}");
        assertThat(prodYaml + bankYaml).doesNotContain("FRAUD_CASE_WORK_QUEUE_SLA:PT24H");
        assertThat(prodYaml + bankYaml).doesNotContain("FRAUD_CASE_WORK_QUEUE_CURSOR_SIGNING_SECRET:");
    }

    @Test
    void missingZeroNegativeAndMalformedSlaShouldFailBinding() {
        contextRunner.run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.fraud-cases.work-queue.sla=PT0S")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.fraud-cases.work-queue.sla=-PT1S")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.fraud-cases.work-queue.sla=not-a-duration")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.fraud-cases.work-queue.sla=PT8H")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void explicitPositiveSlaShouldBind() {
        contextRunner
                .withPropertyValues(
                        "app.fraud-cases.work-queue.sla=PT6H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=test-work-queue-cursor-secret"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(FraudCaseWorkQueueProperties.class)
                        .extracting(FraudCaseWorkQueueProperties::sla)
                        .isEqualTo(Duration.ofHours(6)));
        contextRunner
                .withPropertyValues(
                        "app.fraud-cases.work-queue.sla=PT8H",
                        "app.fraud-cases.work-queue.cursor-signing-secret=test-work-queue-cursor-secret"
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(FraudCaseWorkQueueProperties.class)
                        .satisfies(properties -> {
                            assertThat(properties.sla()).isEqualTo(Duration.ofHours(8));
                            assertThat(properties.cursorSigningSecret()).isEqualTo("test-work-queue-cursor-secret");
                        }));
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("alert-service"))) {
            return root;
        }
        return Path.of("..");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FraudCaseWorkQueueProperties.class)
    static class PropertiesConfig {
    }
}
