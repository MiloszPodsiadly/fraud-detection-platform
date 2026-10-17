package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ModelPromotionPolicyAuthorityConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelPromotionPolicyAuthorityVerifier.class)
    ModelPromotionPolicyAuthorityVerifier unavailableModelPromotionPolicyAuthorityVerifier() {
        return policy -> ModelPromotionPolicyAuthorityVerification.unavailable();
    }
}
