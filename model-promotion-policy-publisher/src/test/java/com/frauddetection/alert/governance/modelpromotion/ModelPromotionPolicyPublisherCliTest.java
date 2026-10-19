package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionPolicyPublisherCliTest {

    @Test
    void noInstitutionalApprovalProviderMeansPublicationIsUnavailable() {
        assertThatThrownBy(() -> ModelPromotionPolicyPublisherCli.main(new String[0]))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("independently trusted publication authorization provider");
    }
}
