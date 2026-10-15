package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactModelShadowPerformanceValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExactModelShadowPerformanceValidator validator = new ExactModelShadowPerformanceValidator();

    @Test
    void acceptsCanonicalPythonGeneratedContract() throws Exception {
        assertThatCode(() -> validator.validate(summary(), selector())).doesNotThrowAnyException();
    }

    @Test
    void rejectsExtraOperationalFieldsAndInconsistentCoverage() throws Exception {
        ObjectNode extra = summary();
        ((ObjectNode) extra.at("/operationalDiagnostics/fallbackRate")).put("unexpected", true);
        assertThatThrownBy(() -> validator.validate(extra, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);

        ObjectNode coverage = summary();
        ((ObjectNode) coverage.get("coverage")).put("status", "NO_DATA");
        assertThatThrownBy(() -> validator.validate(coverage, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);
    }

    private ObjectNode summary() throws Exception {
        return (ObjectNode) objectMapper.readTree(Objects.requireNonNull(getClass().getResourceAsStream(
                "/fixtures/exact-model-shadow-performance/python-generated-v1/exact_model_shadow_performance.json")));
    }

    private ExactModelShadowPerformanceSelector selector() {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", "a".repeat(64),
                "2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"
        );
    }
}
