package com.frauddetection.alert.governance.modelpromotion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class ModelPromotionPolicyAuthorityConfiguration {

    @Bean
    @ConditionalOnMissingBean(ModelPromotionPolicyAuthorityVerifier.class)
    ModelPromotionPolicyAuthorityVerifier modelPromotionPolicyAuthorityVerifier(
            ObjectMapper objectMapper,
            @Value("${app.model-promotion.trusted-issuers.path:}") String trustedIssuersPath,
            @Value("${app.model-promotion.trusted-issuers.minimum-generation:1}") long minimumGeneration,
            org.springframework.beans.factory.ObjectProvider<Clock> clocks,
            org.springframework.beans.factory.ObjectProvider<MeterRegistry> meters
    ) {
        if (trustedIssuersPath == null || trustedIssuersPath.isBlank()) {
            MeterRegistry meterRegistry = meters.getIfAvailable();
            return policy -> {
                if (meterRegistry != null) {
                    Counter.builder("model_promotion_policy_authority_verification_total")
                            .tag("status", "UNAVAILABLE")
                            .tag("reason", "POLICY_TRUST_UNAVAILABLE")
                            .register(meterRegistry).increment();
                }
                return ModelPromotionPolicyAuthorityVerification.unavailable();
            };
        }
        ModelPromotionTrustedIssuerRegistry registry = ModelPromotionTrustedIssuerRegistry.load(
                objectMapper, Path.of(trustedIssuersPath), minimumGeneration
        );
        return new Ed25519ModelPromotionPolicyAuthorityVerifier(objectMapper, registry,
                clocks.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
    }
}
