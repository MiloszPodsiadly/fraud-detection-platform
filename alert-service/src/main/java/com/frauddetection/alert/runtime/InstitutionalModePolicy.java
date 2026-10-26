package com.frauddetection.alert.runtime;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Shared startup policy for institutional runtime boundaries and explicit local single-node mode. */
public final class InstitutionalModePolicy {

    private static final Set<String> INSTITUTIONAL_PROFILES = Set.of("prod", "production", "staging", "bank");
    private static final Set<String> EXPLICIT_LOCAL_SINGLE_NODE_PROFILES = Set.of(
            "local", "dev", "test", "docker-local", "bank-local");

    private InstitutionalModePolicy() {
    }

    public static boolean isInstitutionalMode(Environment environment, boolean bankModeFailClosed) {
        return bankModeFailClosed || hasInstitutionalProfile(environment);
    }

    public static boolean isInstitutionalMode(Environment environment) {
        boolean bankModeFailClosed = environment.getProperty(
                "app.audit.bank-mode.fail-closed", Boolean.class, false);
        return isInstitutionalMode(environment, bankModeFailClosed);
    }

    public static boolean hasInstitutionalProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(INSTITUTIONAL_PROFILES::contains);
    }

    public static boolean isExplicitLocalSingleNodeMode(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles)
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .allMatch(EXPLICIT_LOCAL_SINGLE_NODE_PROFILES::contains);
    }
}
