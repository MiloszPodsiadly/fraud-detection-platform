package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactModelShadowPerformanceSelectorTest {

    @Test
    void selectionKeyMatchesCanonicalPythonArtifact() {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));

        assertThat(selector.selectionKey())
                .isEqualTo("00692a2890340f3fc01296245fad0b48ec30dce3efcd27eca79bb5b74662d149");
    }

    @Test
    void selectionKeyPreservesCanonicalFractionalTimestampText() {
        ExactModelShadowPerformanceSelector selector = new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", "a".repeat(64),
                "2026-06-01T00:00:00.1Z", "2026-06-09T23:59:59.123456789Z"
        );

        assertThat(selector.selectionKey())
                .isEqualTo("5d3decdf6d63c681081974f1b451707e9840b0e4de3c302d8134f1cadb028d83");
    }

    @Test
    void exactArtifactShaAndCanonicalWindowAreMandatory() {
        assertThatThrownBy(() -> selector("A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", "a".repeat(64),
                "2026-06-01T02:00:00+02:00", null
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExactModelShadowPerformanceSelector(
                "customer-id-model", "version", "feature-v2", "a".repeat(64), null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ExactModelShadowPerformanceSelector selector(String sha256) {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model",
                "2026-10-15.shadow",
                "feature-contract-v2",
                sha256,
                "2026-06-01T00:00:00Z",
                "2026-06-09T23:59:59Z"
        );
    }
}
