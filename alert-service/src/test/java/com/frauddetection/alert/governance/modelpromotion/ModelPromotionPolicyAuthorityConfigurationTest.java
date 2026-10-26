package com.frauddetection.alert.governance.modelpromotion;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelPromotionPolicyAuthorityConfigurationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class))
            .withUserConfiguration(ModelPromotionPolicyAuthorityConfiguration.class);

    @Test
    void missingTrustConfigurationSelectsExactlyOneUnavailableVerifier() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ModelPromotionPolicyAuthorityVerifier.class)).hasSize(1);
            assertThat(context.getBean(ModelPromotionPolicyAuthorityVerifier.class)
                    .verify(ModelPromotionAssessmentTestFixtures.policy()).status())
                    .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.UNAVAILABLE);
            assertThat(context.getBean(MeterRegistry.class)
                    .get("model_promotion_policy_authority_verification_total")
                    .tag("status", "UNAVAILABLE").tag("reason", "POLICY_TRUST_UNAVAILABLE")
                    .counter().count()).isEqualTo(1);
        });
    }

    @Test
    void configuredPublicRegistrySelectsTheRealVerifier() throws Exception {
        Path registryPath = tempDir.resolve("policy-issuers.json");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey key = new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                "institutional-policy-issuer", "policy-key-1", Base64.getEncoder().encodeToString(encoded),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                NOW.minus(365, ChronoUnit.DAYS).toString(), NOW.plus(365, ChronoUnit.DAYS).toString(),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
        Files.writeString(registryPath, mapper.writeValueAsString(new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 1, List.of(key))));

        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("app.model-promotion.trusted-issuers.path=" + registryPath).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ModelPromotionPolicyAuthorityVerifier.class)).hasSize(1);
            ModelPromotionPolicyAuthorityVerifier verifier = context.getBean(ModelPromotionPolicyAuthorityVerifier.class);
            assertThat(verifier).isInstanceOf(Ed25519ModelPromotionPolicyAuthorityVerifier.class);
            assertThat(verifier.verify(signedPolicy(pair)).status())
                    .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
            assertThat(context.getBean(MeterRegistry.class)
                    .get("model_promotion_policy_trust_registry_generation").gauge().value()).isEqualTo(1);
        });
    }

    @Test
    void explicitButMissingRegistryFileFailsStartup() {
        contextRunner.withPropertyValues("app.model-promotion.trusted-issuers.path="
                + tempDir.resolve("missing.json")).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidRegistryAndRollbackBelowProtectedGenerationFailStartup() throws Exception {
        Path registryPath = tempDir.resolve("invalid-policy-issuers.json");
        Files.writeString(registryPath, "{not-json");
        contextRunner.withPropertyValues("app.model-promotion.trusted-issuers.path=" + registryPath)
                .run(context -> assertThat(context).hasFailed());

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        ModelPromotionTrustedIssuerRegistry.ConfiguredKey key = new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(
                "institutional-policy-issuer", "policy-key-1", Base64.getEncoder().encodeToString(encoded),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION), List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                NOW.minus(365, ChronoUnit.DAYS).toString(), NOW.plus(365, ChronoUnit.DAYS).toString(),
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
        Files.writeString(registryPath, mapper.writeValueAsString(new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 1, List.of(key))));
        String[] properties = {"app.model-promotion.trusted-issuers.path=" + registryPath,
                "app.model-promotion.trusted-issuers.minimum-generation=2"};
        contextRunner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());

        Files.writeString(registryPath, mapper.writeValueAsString(new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 2, List.of(key))));
        for (int restart = 0; restart < 2; restart++) {
            contextRunner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ModelPromotionPolicyAuthorityVerifier.class)
                        .verify(signedPolicy(pair)).status())
                        .isEqualTo(ModelPromotionPolicyAuthorityVerification.Status.VERIFIED);
            });
        }
    }

    private ModelPromotionPolicy signedPolicy(KeyPair pair) {
        try {
            ModelPromotionPolicy.Content base = ModelPromotionAssessmentTestFixtures.policy().content();
            ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(base.policyVersion(),
                    "spring-wiring-policy", base.revision(),
                    new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                            ModelGovernanceEnvironment.PRODUCTION), base.intendedUse(),
                    new ModelPromotionPolicy.PolicyProvenance(
                            ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                            "policy-author", "risk-committee", NOW.minus(3, ChronoUnit.DAYS).toString(),
                            "approval-1"),
                    NOW.minus(1, ChronoUnit.DAYS).toString(), NOW.plus(1, ChronoUnit.DAYS).toString(),
                    base.criteria());
            String digest = new ModelPromotionPolicyValidator(mapper).canonicalSha256(content);
            String issuedAt = NOW.minus(2, ChronoUnit.DAYS).toString();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(ModelPromotionPolicySignatureContract.signingBytes(mapper, content,
                    "institutional-policy-issuer", "policy-key-1", issuedAt));
            return new ModelPromotionPolicy(content, digest, new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                    "institutional-policy-issuer", "policy-key-1",
                    ModelPromotionPolicySignatureContract.encodeSignature(signer.sign()), digest, issuedAt));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
