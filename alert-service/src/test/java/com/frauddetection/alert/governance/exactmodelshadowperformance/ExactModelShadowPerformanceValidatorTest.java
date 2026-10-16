package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.junit.jupiter.api.Test;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactModelShadowPerformanceValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExactModelShadowPerformanceValidator validator =
            new ExactModelShadowPerformanceValidator(new ModelEvaluationGovernanceValidator());

    @Test
    void acceptsCanonicalPythonGeneratedContractsForBothSourceVerificationLevels() throws Exception {
        assertThatCode(() -> validator.validate(summary("python-generated-v1"), selector()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(summary("python-generated-claim-only-v1"), selector()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrUnknownSourceVerificationLevel() throws Exception {
        ObjectNode unknown = summary();
        ((ObjectNode) unknown.get("sourceEvaluation")).put("sourceDatasetVerificationLevel", "UNKNOWN");
        assertThatThrownBy(() -> validator.validate(unknown, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);

        ObjectNode missing = summary();
        ((ObjectNode) missing.get("sourceEvaluation")).remove("sourceDatasetVerificationLevel");
        assertThatThrownBy(() -> validator.validate(missing, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);
    }

    @Test
    void rejectsExtraOperationalFieldsAndInconsistentCoverage() throws Exception {
        ObjectNode extra = summary();
        ((ObjectNode) extra.at("/operationalDiagnostics/fallbackRate")).put("unexpected", true);
        assertThatThrownBy(() -> validator.validate(extra, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);

        ObjectNode coverage = summary();
        ((ObjectNode) coverage.get("coverage")).put("returnedCohortStatus", "NO_DATA");
        assertThatThrownBy(() -> validator.validate(coverage, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);

        ObjectNode unavailableValue = summary();
        ((ObjectNode) unavailableValue.at("/operationalDiagnostics/fallbackRate")).put("value", 0.0);
        assertThatThrownBy(() -> validator.validate(unavailableValue, selector()))
                .isInstanceOf(ExactModelShadowPerformanceValidationException.class);
    }

    private ObjectNode summary() throws Exception {
        return summary("python-generated-v1");
    }

    private ObjectNode summary(String fixture) throws Exception {
        return (ObjectNode) objectMapper.readTree(Objects.requireNonNull(getClass().getResourceAsStream(
                "/fixtures/exact-model-shadow-performance/" + fixture + "/exact_model_shadow_performance.json")));
    }

    private ExactModelShadowPerformanceSelector selector() {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", "a".repeat(64),
                "2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"
        );
    }
}
