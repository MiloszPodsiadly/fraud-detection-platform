package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineContributionSanitizerTest {
    private final FraudEngineContributionSanitizer sanitizer = new FraudEngineContributionSanitizer();

    @Test
    void truncatesCountAndLongNamesDeterministically() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(2, 10, 5, 1, 5, 20, 8, 120, 256);
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineContributionSummary> result = sanitizer.sanitize(
                "rules.primary",
                List.of(contribution("LONG_SIGNAL_NAME", null), contribution("SECOND", null)),
                strict,
                warnings
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().feature()).isEqualTo("LONG_SIG");
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.CONTRIBUTION_TEXT_TRUNCATED,
                        FraudEngineAggregationWarningCode.CONTRIBUTION_LIMIT_APPLIED
                );
    }

    @Test
    void removesRawValuesAndDropsUnsafeFeatures() {
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineContributionSummary> result = sanitizer.sanitize(
                "rules.primary",
                List.of(
                        contribution("SAFE_SIGNAL", "0.987654"),
                        contribution("customerId.raw", null),
                        contribution("accountId.raw", null),
                        contribution("token.endpoint.secret", null)
                ),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).extracting(BoundedFraudEngineContributionSummary::feature)
                .containsExactly("SAFE_SIGNAL");
        assertThat(result.toString()).doesNotContain("0.987654", "customerId", "accountId", "token", "endpoint", "secret");
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.CONTRIBUTION_VALUE_DROPPED,
                        FraudEngineAggregationWarningCode.CONTRIBUTION_UNSAFE_DROPPED
                );
    }

    private FraudEngineContribution contribution(String feature, String value) {
        return new FraudEngineContribution(feature, value, 0.5d, FraudEngineContributionDirection.INCREASES_RISK);
    }
}
