package com.frauddetection.alert.service;

import com.frauddetection.alert.fraudcase.FraudCaseWorkQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp45FraudCaseWorkQueueSlaConfigTest {

    @Test
    void shouldFailFastWhenWorkQueueSlaIsMissingZeroOrNegative() {
        assertThatThrownBy(() -> new FraudCaseWorkQueueProperties(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FraudCaseWorkQueueProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FraudCaseWorkQueueProperties(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeProductionWorkQueueSlaConfigurationKey() throws Exception {
        String applicationYaml = Files.readString(projectRoot().resolve("alert-service/src/main/resources/application.yml"));
        String applicationProdYaml = Files.readString(projectRoot().resolve("alert-service/src/main/resources/application-prod.yml"));
        assertThat(applicationYaml)
                .contains("fraud-cases:")
                .contains("work-queue:")
                .contains("sla: ${FRAUD_CASE_WORK_QUEUE_SLA:PT24H}");
        assertThat(applicationProdYaml)
                .contains("sla: ${FRAUD_CASE_WORK_QUEUE_SLA}")
                .doesNotContain("FRAUD_CASE_WORK_QUEUE_SLA:PT24H");
        assertThat(new FraudCaseWorkQueueProperties(Duration.ofHours(24)).sla()).isEqualTo(Duration.ofHours(24));
    }

    private Path projectRoot() {
        Path root = Path.of(".");
        if (Files.exists(root.resolve("alert-service"))) {
            return root;
        }
        return Path.of("..");
    }
}
