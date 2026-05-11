package com.frauddetection.alert.fraudcase;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
public class FraudCaseWorkQueueCursorSecretStartupGuard implements InitializingBean {

    static final Set<String> LOCAL_CURSOR_SIGNING_SECRETS = Set.of(
            "local-test-work-queue-cursor-signing-secret",
            "local-dev-work-queue-cursor-secret-change-me"
    );

    private final FraudCaseWorkQueueProperties properties;
    private final Environment environment;

    public FraudCaseWorkQueueCursorSecretStartupGuard(FraudCaseWorkQueueProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (productionLikeProfileActive()
                && LOCAL_CURSOR_SIGNING_SECRETS.contains(properties.cursorSigningSecret())) {
            throw new IllegalStateException("Production-like fraud case work queue cursor signing secret must not use a local default.");
        }
    }

    private boolean productionLikeProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.contains("prod") || profile.contains("bank"));
    }
}
