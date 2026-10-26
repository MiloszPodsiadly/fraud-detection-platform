package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstitutionalModelPromotionStartupGuardTest {

    @Test
    void localModeDoesNotRequireInstitutionalRegistriesOrPersistenceGuard() {
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        var guard = new InstitutionalModelPromotionStartupGuard(
                environment("docker-local", false), registries, false);

        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void unexpectedProfileWithBankFailClosedRequiresAllTrustRegistries() {
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(registries.requireReady(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS))
                .thenThrow(new SecurityException("Validated trust registry unavailable"));
        var guard = new InstitutionalModelPromotionStartupGuard(environment("evaluation", true), registries, true);

        assertThrows(SecurityException.class,
                () -> guard.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void institutionalModeRejectsDisabledPersistenceStartupVerification() {
        var guard = new InstitutionalModelPromotionStartupGuard(
                environment("bank", false), mock(PolicyTrustRegistrySnapshotService.class), false);

        assertThrows(IllegalStateException.class,
                () -> guard.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void institutionalModeRequiresActorApprovalAndIssuerRegistries() {
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        var guard = new InstitutionalModelPromotionStartupGuard(
                environment("staging", false), registries, true);

        assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments(new String[0])));
        for (var registry : PolicyTrustRegistrySnapshotService.Registry.values()) {
            verify(registries).requireReady(registry);
        }
    }

    private static MockEnvironment environment(String profile, boolean bankModeFailClosed) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        if (bankModeFailClosed) environment.setProperty("app.audit.bank-mode.fail-closed", "true");
        return environment;
    }
}
