package com.frauddetection.scoring.orchestration.aggregation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineAggregationPolicyTest {

    @Test
    void defaultPolicyIsBounded() {
        FraudEngineAggregationPolicy policy = FraudEngineAggregationPolicy.defaultInternalPolicy();

        assertThat(policy.maxEngineResults()).isEqualTo(2);
        assertThat(policy.maxStrongestSignals()).isEqualTo(5);
        assertThat(policy.maxEvidenceItemsPerEngine()).isEqualTo(5);
        assertThat(policy.maxEvidenceTitleLength()).isEqualTo(120);
        assertThat(policy.maxEvidenceDescriptionLength()).isEqualTo(256);
    }

    @Test
    void rejectsZeroAndNegativeLimits() {
        assertThatThrownBy(() -> policy(0, 5, 120, 256))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXENGINERESULTS");
        assertThatThrownBy(() -> policy(2, -1, 120, 256))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXSTRONGESTSIGNALS");
    }

    @Test
    void rejectsExcessiveLimits() {
        assertThatThrownBy(() -> policy(17, 5, 120, 256))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXENGINERESULTS");
        assertThatThrownBy(() -> policy(2, 17, 120, 256))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXSTRONGESTSIGNALS");
        assertThatThrownBy(() -> policy(2, 5, 121, 256))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXEVIDENCETITLELENGTH");
        assertThatThrownBy(() -> policy(2, 5, 120, 257))
                .hasMessage("AGGREGATION_POLICY_INVALID_MAXEVIDENCEDESCRIPTIONLENGTH");
    }

    static FraudEngineAggregationPolicy policy(int engineResults, int strongestSignals, int titleLength, int descriptionLength) {
        return new FraudEngineAggregationPolicy(engineResults, 10, 5, 5, strongestSignals, 20, 128, titleLength, descriptionLength);
    }
}
