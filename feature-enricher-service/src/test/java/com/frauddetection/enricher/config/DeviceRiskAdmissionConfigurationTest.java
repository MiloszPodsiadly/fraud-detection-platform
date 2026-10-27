package com.frauddetection.enricher.config;

import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.enricher.device.DisabledDeviceRiskAdmissionAdapter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
    void spoofedIssuerScopeFlagsSealAndSyntheticClaimsCannotEscalateAdmission() {
        var baseline = TransactionFixtures.rawTransaction().build();
        var forgedDevice = new DeviceInfo(
                baseline.deviceInfo().deviceId(), baseline.deviceInfo().fingerprint(), baseline.deviceInfo().ipAddress(),
                baseline.deviceInfo().userAgent(), baseline.deviceInfo().platform(), baseline.deviceInfo().browser(),
                true, true, true,
                Map.of("issuer", "BANK", "signature", "self-signed-device-seal"));
        var spoofedRequests = List.of(
                withClaims(baseline, "INSTITUTIONAL_DEVICE_GATEWAY", baseline.deviceInfo(),
                        Map.of("admissionScope", "DEVICE_RISK", "institutionId", "bank-001")),
                withClaims(baseline, baseline.sourceSystem(), forgedDevice,
                        Map.of("coverageSeal", "forged-seal", "signature", "not-verified")),
                withClaims(baseline, "SYNTHETIC_REPLAY", forgedDevice,
                        Map.of("synthetic", true, "sourceQuality", "AUTHENTICATED_INSTITUTION"))
        );

        contextRunner.run(context -> {
            var adapter = context.getBean(DisabledDeviceRiskAdmissionAdapter.class);
            assertThat(spoofedRequests)
                    .allSatisfy(request -> assertThat(adapter.admit(request).reasonCode())
                            .isEqualTo(DisabledDeviceRiskAdmissionAdapter.DENIAL_CODE));
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

    private static TransactionRawEvent withClaims(
            TransactionRawEvent baseline,
            String sourceSystem,
            DeviceInfo deviceInfo,
            Map<String, Object> attributes
    ) {
        return new TransactionRawEvent(
                baseline.eventId(), baseline.transactionId(), baseline.correlationId(), baseline.customerId(),
                baseline.accountId(), baseline.paymentInstrumentId(), baseline.createdAt(), baseline.transactionTimestamp(),
                baseline.transactionAmount(), baseline.merchantInfo(), deviceInfo, baseline.locationInfo(),
                baseline.customerContext(), baseline.transactionType(), baseline.authorizationMethod(), sourceSystem,
                baseline.traceId(), attributes);
    }
}
