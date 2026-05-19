package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessMethodClassifierTest {

    private final SecurityDeniedAccessMethodClassifier classifier = new SecurityDeniedAccessMethodClassifier();

    @Test
    void mapsAllowedMethodsExactly() {
        assertThat(classifier.classify("GET")).isEqualTo("GET");
        assertThat(classifier.classify("POST")).isEqualTo("POST");
        assertThat(classifier.classify("PUT")).isEqualTo("PUT");
        assertThat(classifier.classify("PATCH")).isEqualTo("PATCH");
        assertThat(classifier.classify("DELETE")).isEqualTo("DELETE");
    }

    @Test
    void mapsUnsupportedOrMissingMethodsToOther() {
        assertThat(classifier.classify("TRACE")).isEqualTo("OTHER");
        assertThat(classifier.classify("OPTIONS")).isEqualTo("OTHER");
        assertThat(classifier.classify("HEAD")).isEqualTo("OTHER");
        assertThat(classifier.classify(null)).isEqualTo("OTHER");
        assertThat(classifier.classify(" ")).isEqualTo("OTHER");
    }
}
