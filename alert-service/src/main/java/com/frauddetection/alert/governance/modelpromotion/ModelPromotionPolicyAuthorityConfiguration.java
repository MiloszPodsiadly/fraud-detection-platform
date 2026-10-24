package com.frauddetection.alert.governance.modelpromotion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
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
        MeterRegistry meterRegistry = meters.getIfAvailable();
        if (meterRegistry != null) {
            Gauge.builder("model_promotion_policy_trust_registry_generation", registry,
                    ModelPromotionTrustedIssuerRegistry::generation).register(meterRegistry);
        }
        return new Ed25519ModelPromotionPolicyAuthorityVerifier(objectMapper, registry,
                clocks.getIfAvailable(Clock::systemUTC), meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(ModelPromotionPolicyApprovalEvidenceVerifier.class)
    ModelPromotionPolicyApprovalEvidenceVerifier modelPromotionPolicyApprovalEvidenceVerifier(
            MongoTemplate mongo, ObjectMapper mapper,
            @Value("${app.model-promotion.approval-trust.path:}") String approvalPath,
            @Value("${app.model-promotion.approval-trust.minimum-generation:1}") long approvalFloor,
            @Value("${app.model-promotion.online-actor-trust.path:}") String actorPath,
            @Value("${app.model-promotion.online-actor-trust.minimum-generation:1}") long actorFloor,
            @Value("${app.model-policy-webauthn.rp-id:localhost}") String rpId,
            @Value("${app.model-policy-webauthn.origin:http://localhost:4173}") String origin,
            org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
        if (approvalPath == null || approvalPath.isBlank()) {
            return policy -> ModelPromotionPolicyApprovalEvidenceVerification.unavailable(
                    ModelPromotionPolicyApprovalEvidenceVerification.Reason.TRUST_UNAVAILABLE);
        }
        InternalPolicyApprovalTrustRegistry approval = InternalPolicyApprovalTrustRegistry.load(
                mapper, Path.of(approvalPath), approvalFloor);
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        OnlinePolicyApprovalVerifier online = null;
        if (actorPath != null && !actorPath.isBlank()) {
            OnlinePolicyActorTrustRegistry actors = OnlinePolicyActorTrustRegistry.load(
                    mapper, Path.of(actorPath), actorFloor);
            online = new OnlinePolicyApprovalVerifier(mongo, mapper, clock, rpId, origin, actors, approval);
        }
        return new MongoModelPromotionPolicyApprovalEvidenceVerifier(mongo, mapper, clock, approval, online);
    }

    @Bean
    ModelPromotionPolicyFullTrustVerifier modelPromotionPolicyFullTrustVerifier(
            ModelPromotionPolicyAuthorityVerifier signatures,
            ModelPromotionPolicyApprovalEvidenceVerifier approvals) {
        return new ModelPromotionPolicyFullTrustVerifier(signatures, approvals);
    }
}
