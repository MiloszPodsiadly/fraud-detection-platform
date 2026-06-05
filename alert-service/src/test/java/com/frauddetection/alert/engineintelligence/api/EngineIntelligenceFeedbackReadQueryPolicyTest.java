package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.InvalidEngineIntelligenceFeedbackRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceFeedbackReadQueryPolicyTest {

    private final EngineIntelligenceFeedbackReadQueryPolicy policy = new EngineIntelligenceFeedbackReadQueryPolicy();

    @Test
    void queryPolicyAllowsOnlyLimit() {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("limit", "25");

        assertThat(policy.limit(parameters)).isEqualTo(25);

        parameters.add("cursor", "token-secret-stacktrace");
        assertThatThrownBy(() -> policy.limit(parameters))
                .isInstanceOf(InvalidEngineIntelligenceFeedbackRequestException.class)
                .hasMessageNotContaining("cursor")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("stacktrace");
    }

    @Test
    void missingLimitUsesDefaultLimit() {
        assertThat(policy.limit(new LinkedMultiValueMap<>())).isEqualTo(25);
    }
}
