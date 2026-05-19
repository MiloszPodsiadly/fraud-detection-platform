package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessAuthStateClassifierTest {

    private final SecurityDeniedAccessAuthStateClassifier classifier = new SecurityDeniedAccessAuthStateClassifier();

    @Test
    void classifiesNullAndUnauthenticatedAsAnonymous() {
        TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken("analyst@example.com", null);
        unauthenticated.setAuthenticated(false);

        assertThat(classifier.classify(null)).isEqualTo("anonymous");
        assertThat(classifier.classify(unauthenticated)).isEqualTo("anonymous");
    }

    @Test
    void classifiesAuthenticatedWithoutPrincipalData() {
        TestingAuthenticationToken authenticated = new TestingAuthenticationToken("m.podsiadly99@gmail.com", null);
        authenticated.setAuthenticated(true);

        assertThat(classifier.classify(authenticated))
                .isEqualTo("authenticated")
                .doesNotContain("m.podsiadly99@gmail.com");
    }
}
