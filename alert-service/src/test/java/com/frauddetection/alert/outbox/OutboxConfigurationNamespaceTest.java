package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxConfigurationNamespaceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    ApplicationConversionService.getSharedInstance()
            ))
            .withBean(FraudDecisionEventPublisher.class, () -> mock(FraudDecisionEventPublisher.class))
            .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class))
            .withBean(AlertServiceMetrics.class, () -> mock(AlertServiceMetrics.class))
            .withBean(OutboxOperationalControls.class, () -> new OutboxOperationalControls(true, true))
            .withBean(OutboxPublisherCoordinator.class);

    @Test
    void canonicalNamespaceDrivesPublisherSettingsDespiteRetiredSelectorValues() {
        contextRunner.withPropertyValues(
                "app.outbox.lease-duration=PT17S",
                "app.outbox.max-attempts=9",
                "app.alert.decision-outbox.lease-duration=PT99S",
                "app.alert.decision-outbox.max-attempts=99"
        ).run(context -> {
            OutboxPublisherCoordinator coordinator = context.getBean(OutboxPublisherCoordinator.class);

            assertThat(ReflectionTestUtils.getField(coordinator, "leaseDuration"))
                    .isEqualTo(Duration.ofSeconds(17));
            assertThat(ReflectionTestUtils.getField(coordinator, "maxAttempts")).isEqualTo(9);
        });
    }

    @Test
    void retiredSelectorsAloneCannotOverrideCurrentDefaults() {
        contextRunner.withPropertyValues(
                "app.alert.decision-outbox.lease-duration=PT99S",
                "app.alert.decision-outbox.max-attempts=99"
        ).run(context -> {
            OutboxPublisherCoordinator coordinator = context.getBean(OutboxPublisherCoordinator.class);

            assertThat(ReflectionTestUtils.getField(coordinator, "leaseDuration"))
                    .isEqualTo(Duration.ofMinutes(1));
            assertThat(ReflectionTestUtils.getField(coordinator, "maxAttempts")).isEqualTo(5);
        });
    }

    @Test
    void runtimeConfigurationContainsOnlyCanonicalSelectors() throws IOException {
        String publisher = source("outbox/OutboxPublisherCoordinator.java");
        String fraudAlertPublisher = source("outbox/FraudAlertOutboxPublisher.java");
        String scheduler = source("service/FraudDecisionOutboxPublisher.java");
        String trust = source("system/trustlevel/application/SystemTrustLevelService.java");
        String outboxHealth = source("system/trustlevel/health/OutboxRecoveryHealthCollector.java");
        String bankGuard = source("regulated/BankModeStartupGuard.java");
        String externalAudit = source("audit/external/ExternalAuditAnchorSinkConfiguration.java");
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String compose = Files.readString(Path.of("..", "deployment", "docker-compose.yml"));

        assertThat(publisher).contains("${app.outbox.lease-duration:PT1M}", "${app.outbox.max-attempts:5}");
        assertThat(fraudAlertPublisher)
                .contains(
                        "${app.outbox.lease-duration:PT1M}",
                        "${app.outbox.max-attempts:5}",
                        "${app.outbox.publisher.delay-ms:5000}"
                );
        assertThat(scheduler).contains("${app.outbox.publisher.delay-ms:5000}");
        assertThat(outboxHealth).contains("${app.outbox.stale-threshold:PT10M}");
        assertThat(bankGuard).contains("${app.outbox.max-attempts:5}");
        assertThat(externalAudit)
                .contains("${app.audit.external-anchoring.object-store.startup-check-enabled:true}")
                .doesNotContain("app.audit.external-store.startup-validation");
        assertThat(application)
                .contains("${OUTBOX_PUBLISHER_ENABLED:true}")
                .contains("${OUTBOX_RECOVERY_ENABLED:true}")
                .contains("${OUTBOX_LEASE_DURATION:PT1M}")
                .contains("${OUTBOX_MAX_ATTEMPTS:5}")
                .contains("${OUTBOX_STALE_THRESHOLD:PT10M}")
                .doesNotContain("APP_ALERT_DECISION_OUTBOX");
        assertThat(compose)
                .contains(
                        "OUTBOX_PUBLISHER_ENABLED",
                        "OUTBOX_RECOVERY_ENABLED",
                        "OUTBOX_LEASE_DURATION",
                        "OUTBOX_MAX_ATTEMPTS",
                        "OUTBOX_STALE_THRESHOLD"
                )
                .contains("AUDIT_EXTERNAL_ANCHORING_OBJECT_STORE_STARTUP_CHECK_ENABLED");
        assertThat(publisher + fraudAlertPublisher + scheduler + trust + outboxHealth + bankGuard + application + compose)
                .doesNotContain("app.alert.decision-outbox", "APP_ALERT_DECISION_OUTBOX");
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/frauddetection/alert", relativePath));
    }
}
