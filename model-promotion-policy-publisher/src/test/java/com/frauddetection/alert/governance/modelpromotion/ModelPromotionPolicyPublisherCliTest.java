package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicyPublisherCliTest {

    @Test
    void firstPartyWorkflowRequiresAnExplicitCommand() {
        assertThatThrownBy(() -> ModelPromotionPolicyPublisherCli.main(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage-approved-trust or verify-loaded-trust");
    }
}
