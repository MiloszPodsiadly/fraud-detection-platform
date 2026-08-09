package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.features.VelocityFeatureContract;
import com.frauddetection.scoring.features.FeatureSnapshotValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityInputValidatorTest {
    private final VelocityInputValidator validator = new VelocityInputValidator();

    @Test
    void acceptsCanonicalConsistentCountWindowAndRate() {
        assertReady(inputs(0, "PT1M", "0.00", 0.0d));
        assertReady(inputs(1, "PT1M", "0.00", 1.0d));
        assertReady(inputs(5, "PT1M", "0.00", 5.0d));
    }

    @Test
    void acceptsMismatchJustInsideTolerance() {
        assertReady(inputs(5, "PT1M", "0.00",
                5.0d + VelocityFeatureContract.RATE_CONSISTENCY_TOLERANCE));
    }

    @Test
    void rejectsMismatchJustOutsideTolerance() {
        assertDegraded(
                inputs(5, "PT1M", "0.00",
                        5.0d + VelocityFeatureContract.RATE_CONSISTENCY_TOLERANCE + 0.00001d),
                VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT
        );
    }

    @Test
    void rejectsInconsistentCountAndRateRelations() {
        assertDegraded(inputs(1, "PT1M", "0.00", 100.0d),
                VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT);
        assertDegraded(inputs(100, "PT1M", "0.00", 1.0d),
                VelocitySignalReasonCode.VELOCITY_FEATURES_INCONSISTENT);
    }

    @Test
    void rejectsSharedInvalidVelocitySemanticFixtures() throws Exception {
        JsonNode cases = JsonMapper.builder().findAndAddModules().build()
                .readTree(sharedInvalidSemanticFixture().toFile())
                .get("cases");

        for (JsonNode semanticCase : StreamSupport.stream(cases.spliterator(), false)
                .filter(node -> "velocity-input".equals(node.get("category").textValue()))
                .toList()) {
            JsonNode input = semanticCase.get("velocityInput");

            assertDegraded(
                    inputs(
                            input.get("recentTransactionCount").intValue(),
                            input.get("recentTransactionCountWindow").textValue(),
                            input.get("recentAmountSumPln").textValue(),
                            input.get("transactionVelocityPerMinute").doubleValue()
                    ),
                    VelocitySignalReasonCode.valueOf(semanticCase.get("expectedReason").textValue())
            );
        }
    }

    @Test
    void rejectsInvalidDomains() {
        assertDegraded(inputs(-1, "PT1M", "0.00", 1.0d),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
        assertDegraded(inputs(1, "PT1M", "0.00", -1.0d),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
        assertDegraded(inputs(1, "PT1M", "0.00", Double.NaN),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
        assertDegraded(inputs(1, "PT1M", "0.00", Double.POSITIVE_INFINITY),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
        assertDegraded(inputs(1, "PT1M", "0.00", Double.NEGATIVE_INFINITY),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
    }

    @Test
    void missingFactsAreUnavailableNotZero() {
        assertUnavailable(new VelocityInputs(
                FeatureSnapshotValue.missing("recentTransactionCount"),
                FeatureSnapshotValue.present("recentTransactionCountWindow", "PT1M"),
                FeatureSnapshotValue.present("recentAmountSumPln", BigDecimal.ZERO),
                FeatureSnapshotValue.present("transactionVelocityPerMinute", 1.0d)
        ));
        assertUnavailable(new VelocityInputs(
                FeatureSnapshotValue.present("recentTransactionCount", 1),
                FeatureSnapshotValue.present("recentTransactionCountWindow", "PT1M"),
                FeatureSnapshotValue.present("recentAmountSumPln", BigDecimal.ZERO),
                FeatureSnapshotValue.missing("transactionVelocityPerMinute")
        ));
        assertUnavailable(new VelocityInputs(
                FeatureSnapshotValue.present("recentTransactionCount", 1),
                FeatureSnapshotValue.missing("recentTransactionCountWindow"),
                FeatureSnapshotValue.present("recentAmountSumPln", BigDecimal.ZERO),
                FeatureSnapshotValue.present("transactionVelocityPerMinute", 1.0d)
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT60S", "PT59S", "PT2M", "PT1H", "PT24H", "PT0S", "PT-1S", "not-a-duration"})
    void rejectsNonCanonicalOrMalformedWindow(String window) {
        assertDegraded(inputs(1, window, "0.00", 1.0d),
                VelocitySignalReasonCode.VELOCITY_FEATURE_VALUE_INVALID);
    }

    @Test
    void realZeroRemainsValidZero() {
        VelocityInputValidation validation = validator.validate(inputs(0, "PT1M", "0.00", 0.0d));

        assertThat(validation.readiness()).isEqualTo(VelocityInputReadiness.READY);
        assertThat(validation.validated().recentTransactionCount()).isZero();
        assertThat(validation.validated().transactionVelocityPerMinute()).isZero();
    }

    private void assertReady(VelocityInputs inputs) {
        assertThat(validator.validate(inputs).readiness()).isEqualTo(VelocityInputReadiness.READY);
    }

    private void assertUnavailable(VelocityInputs inputs) {
        VelocityInputValidation validation = validator.validate(inputs);

        assertThat(validation.readiness()).isEqualTo(VelocityInputReadiness.UNAVAILABLE);
        assertThat(validation.reasonCode()).isEqualTo(VelocitySignalReasonCode.VELOCITY_FEATURES_UNAVAILABLE);
    }

    private void assertDegraded(VelocityInputs inputs, VelocitySignalReasonCode reasonCode) {
        VelocityInputValidation validation = validator.validate(inputs);

        assertThat(validation.readiness()).isEqualTo(VelocityInputReadiness.DEGRADED);
        assertThat(validation.reasonCode()).isEqualTo(reasonCode);
        assertThat(validation.validated()).isNull();
    }

    private VelocityInputs inputs(int count, String window, String amountPln, double rate) {
        return new VelocityInputs(
                FeatureSnapshotValue.present("recentTransactionCount", count),
                FeatureSnapshotValue.present("recentTransactionCountWindow", window),
                FeatureSnapshotValue.present("recentAmountSumPln", new BigDecimal(amountPln)),
                FeatureSnapshotValue.present("transactionVelocityPerMinute", rate)
        );
    }

    private Path sharedInvalidSemanticFixture() {
        Path fromRoot = Path.of(
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/invalid_semantic_cases.json"
        );
        if (fromRoot.toFile().isFile()) {
            return fromRoot;
        }
        return Path.of(
                "..",
                "common-events",
                "src/test/resources/fixtures/engine-intelligence/invalid_semantic_cases.json"
        );
    }
}
