package com.frauddetection.enricher.config;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.device.DisabledDeviceRiskAdmissionAdapter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceRiskAdmissionConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeviceRiskAdmissionConfiguration.class);

    @Test
    void admissionDefaultsDisabledAndRejectsEveryUnverifiedRequest() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DeviceRiskAdmissionProperties.class).enabled()).isFalse();
            assertThat(context.getBean(DisabledDeviceRiskAdmissionAdapter.class)
                    .admit(TransactionFixtures.rawTransaction().build()).reasonCode())
                    .isEqualTo(DisabledDeviceRiskAdmissionAdapter.DENIAL_CODE);
        });
    }

    @Test
    void enablingAdmissionFailsStartupWithoutTrustedIssuer() {
        contextRunner.withPropertyValues(DeviceRiskAdmissionProperties.PROPERTY_NAME + "=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("fraud.device-risk.admission.enabled is unsupported until an "
                                    + "authenticated issuer, scoped authorization, and signed coverage seal are "
                                    + "implemented");
                });
    }

    @Test
    void applicationYamlKeepsAdmissionDisabledAndFeatureStoreTtlBoundToItsOwner() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(application.getProperty(DeviceRiskAdmissionProperties.PROPERTY_NAME)).isEqualTo(false);
        assertThat(application.getProperty("app.feature-store.last-transaction-ttl")).isEqualTo("P30D");
    }
}
