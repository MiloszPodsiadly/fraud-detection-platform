package com.frauddetection.trustauthority;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class TrustAuthorityProfilePolicy {

    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");
    private static final Set<String> LOCAL_KEY_GENERATION_PROFILES = Set.of("local", "dev", "test", "docker-local");

    private TrustAuthorityProfilePolicy() {
    }

    static boolean productionLike(Environment environment) {
        return normalizedProfiles(environment).stream().anyMatch(PRODUCTION_PROFILES::contains);
    }

    static boolean localKeyGenerationAllowed(Environment environment) {
        Set<String> profiles = normalizedProfiles(environment);
        return profiles.stream().noneMatch(PRODUCTION_PROFILES::contains)
                && (profiles.isEmpty() || profiles.stream().anyMatch(LOCAL_KEY_GENERATION_PROFILES::contains));
    }

    static Set<String> normalizedProfiles(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
