package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void dropsMalformedContributionsWithoutThrowingAndKeepsSafeContribution() {
        List<FraudEngineContribution> source = new ArrayList<>();
        source.add(null);
        source.add(malformedContribution(null, null, FraudEngineContributionDirection.INCREASES_RISK));
        source.add(malformedContribution(" ", null, FraudEngineContributionDirection.INCREASES_RISK));
        source.add(malformedContribution("SAFE_WITHOUT_DIRECTION", null, null));
        source.add(contribution("SAFE_SIGNAL", "raw-number-0.42"));
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineContributionSummary> result = sanitizer.sanitize(
                "rules.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).extracting(BoundedFraudEngineContributionSummary::feature)
                .containsExactly("SAFE_SIGNAL");
        assertThat(result.toString()).doesNotContain("raw-number-0.42");
        assertThat(warnings).extracting(FraudEngineAggregationWarning::code)
                .contains(
                        FraudEngineAggregationWarningCode.CONTRIBUTION_UNSAFE_DROPPED,
                        FraudEngineAggregationWarningCode.CONTRIBUTION_VALUE_DROPPED
                );
    }

    @Test
    void dropsUnsafeIdentifierVariantFeaturesWithoutRawLeakage() {
        List<String> forbidden = forbiddenVariants();
        List<FraudEngineContribution> source = forbidden.stream()
                .map(feature -> malformedContribution(feature, null, FraudEngineContributionDirection.INCREASES_RISK))
                .toList();
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();

        List<BoundedFraudEngineContributionSummary> result = sanitizer.sanitize(
                "rules.primary",
                source,
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                warnings
        );

        assertThat(result).isEmpty();
        forbidden.forEach(raw -> assertThat(result + warnings.toString()).doesNotContain(raw));
    }

    private FraudEngineContribution contribution(String feature, String value) {
        return new FraudEngineContribution(feature, value, 0.5d, FraudEngineContributionDirection.INCREASES_RISK);
    }

    private FraudEngineContribution malformedContribution(
            String feature,
            String value,
            FraudEngineContributionDirection direction
    ) {
        FraudEngineContribution contribution = mock(FraudEngineContribution.class);
        when(contribution.feature()).thenReturn(feature);
        when(contribution.value()).thenReturn(value);
        when(contribution.weight()).thenReturn(0.5d);
        when(contribution.direction()).thenReturn(direction);
        return contribution;
    }

    private List<String> forbiddenVariants() {
        return List.of(
                "transaction_id",
                "txn_id",
                "customer_id",
                "cust_id",
                "account_id",
                "acct_id",
                "card_id",
                "merchant_id",
                "merchant-id",
                "accessToken",
                "bearerToken",
                "stack_trace",
                "exceptionMessage",
                "raw_feature_vector",
                "model_endpoint",
                "apiKey",
                "authorizationBearer"
        );
    }
}
