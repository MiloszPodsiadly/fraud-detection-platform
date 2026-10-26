package com.frauddetection.alert.governance.modelpolicy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyWebAuthnOriginConfigurationTest {

    @Test
    void localDefaultOriginRemainsValidOutsideInstitutionalMode() {
        assertDoesNotThrow(() -> PolicyWebAuthnService.validateOrigin(
                "localhost", "http://localhost:4173", environment("docker-local", false)));
    }

    @Test
    void stagingRejectsLocalhostOrigin() {
        assertThrows(IllegalStateException.class, () -> PolicyWebAuthnService.validateOrigin(
                "localhost", "http://localhost:4173", environment("staging", false)));
    }

    @Test
    void unexpectedProfileWithBankFailClosedRejectsLocalhostOrigin() {
        assertThrows(IllegalStateException.class, () -> PolicyWebAuthnService.validateOrigin(
                "localhost", "http://localhost:4173", environment("evaluation", true)));
    }

    @Test
    void institutionalModeAcceptsConfiguredNonLocalHttpsOrigin() {
        assertDoesNotThrow(() -> PolicyWebAuthnService.validateOrigin(
                "fraud.example.test", "https://fraud.example.test", environment("bank", false)));
    }

    private MockEnvironment environment(String profile, boolean bankModeFailClosed) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        if (bankModeFailClosed) environment.setProperty("app.audit.bank-mode.fail-closed", "true");
        return environment;
    }
}
