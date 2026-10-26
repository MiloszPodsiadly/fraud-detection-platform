package com.frauddetection.alert.audit.read;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveReadAuditPolicyTest {

    @Test
    void productionLikeProfilesAlwaysRequireDurableSensitiveReadAudit() {
        for (String profile : new String[]{"prod", "production", "staging", "bank"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            SensitiveReadAuditPolicy policy = new SensitiveReadAuditPolicy(
                    environment, false, false);

            assertThat(policy.failClosed()).as(profile).isTrue();
        }
    }

    @Test
    void localDevelopmentPolicyPermitsBestEffortWhenFailClosedSwitchesAreOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        SensitiveReadAuditPolicy policy = new SensitiveReadAuditPolicy(environment, false, false);

        assertThat(policy.failClosed()).isFalse();
    }

    @Test
    void explicitFailClosedSettingAppliesToLocalProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        SensitiveReadAuditPolicy policy = new SensitiveReadAuditPolicy(environment, false, true);

        assertThat(policy.failClosed()).isTrue();
    }
}
