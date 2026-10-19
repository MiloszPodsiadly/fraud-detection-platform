package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyAuthorityVerification.Reason;

/** Read-only verification against deployment-controlled, startup-validated public keys. */
final class Ed25519ModelPromotionPolicyAuthorityVerifier implements ModelPromotionPolicyAuthorityVerifier {

    static final long MAX_FUTURE_ISSUANCE_SKEW_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final ModelPromotionTrustedIssuerRegistry registry;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    Ed25519ModelPromotionPolicyAuthorityVerifier(ObjectMapper objectMapper,
            ModelPromotionTrustedIssuerRegistry registry, Clock clock) {
        this(objectMapper, registry, clock, null);
    }

    Ed25519ModelPromotionPolicyAuthorityVerifier(ObjectMapper objectMapper,
            ModelPromotionTrustedIssuerRegistry registry, Clock clock, MeterRegistry meterRegistry) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ModelPromotionPolicyAuthorityVerification verify(ModelPromotionPolicy policy) {
        ModelPromotionPolicyAuthorityVerification result = verifyForCurrentAuthorization(policy);
        if (meterRegistry != null) {
            Counter.builder("model_promotion_policy_authority_verification_total")
                    .tag("status", result.status().name())
                    .tag("reason", result.reason().name())
                    .register(meterRegistry).increment();
        }
        return result;
    }

    /** Historical signature integrity is never a substitute for current operational authorization. */
    ModelPromotionPolicyHistoricalIntegrity inspectHistoricalIntegrity(ModelPromotionPolicy policy) {
        if (!institutional(policy)) {
            return new ModelPromotionPolicyHistoricalIntegrity(
                    ModelPromotionPolicyHistoricalIntegrity.Status.INVALID, Reason.POLICY_CONTRACT_INVALID);
        }
        ModelPromotionTrustedIssuerRegistry.TrustedKey key = registry.findByKeyId(
                policy.authorizationEvidence().keyId());
        if (key == null) {
            return new ModelPromotionPolicyHistoricalIntegrity(
                    ModelPromotionPolicyHistoricalIntegrity.Status.INVALID, Reason.POLICY_KEY_UNKNOWN);
        }
        if (!key.issuerIdentity().equals(policy.authorizationEvidence().authorityIdentity())
                || !authorized(key, policy.content())) {
            return new ModelPromotionPolicyHistoricalIntegrity(
                    ModelPromotionPolicyHistoricalIntegrity.Status.INVALID, Reason.POLICY_ISSUER_UNAUTHORIZED);
        }
        try {
            Reason reason = checkSignedPolicy(policy, key, clock.instant(), false);
            if (reason != Reason.NONE) {
                return new ModelPromotionPolicyHistoricalIntegrity(
                        ModelPromotionPolicyHistoricalIntegrity.Status.INVALID, reason);
            }
            ModelPromotionPolicyHistoricalIntegrity.Status status = switch (key.status()) {
                case ACTIVE -> ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_ACTIVE_KEY;
                case RETIRED -> ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_RETIRED_KEY;
                case REVOKED -> ModelPromotionPolicyHistoricalIntegrity.Status.INTACT_REVOKED_KEY;
            };
            return new ModelPromotionPolicyHistoricalIntegrity(status, Reason.NONE);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            return new ModelPromotionPolicyHistoricalIntegrity(
                    ModelPromotionPolicyHistoricalIntegrity.Status.UNAVAILABLE, Reason.POLICY_TRUST_UNAVAILABLE);
        }
    }

    private ModelPromotionPolicyAuthorityVerification verifyForCurrentAuthorization(ModelPromotionPolicy policy) {
        if (!institutional(policy)) {
            return ModelPromotionPolicyAuthorityVerification.rejected(Reason.POLICY_CONTRACT_INVALID);
        }
        ModelPromotionTrustedIssuerRegistry.TrustedKey key = registry.findByKeyId(
                policy.authorizationEvidence().keyId());
        if (key == null) {
            return ModelPromotionPolicyAuthorityVerification.rejected(Reason.POLICY_KEY_UNKNOWN);
        }
        if (!key.issuerIdentity().equals(policy.authorizationEvidence().authorityIdentity())
                || !authorized(key, policy.content())) {
            return ModelPromotionPolicyAuthorityVerification.rejected(Reason.POLICY_ISSUER_UNAUTHORIZED);
        }
        if (key.status() == ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED) {
            return ModelPromotionPolicyAuthorityVerification.rejected(Reason.POLICY_KEY_REVOKED);
        }
        if (key.status() == ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED) {
            return ModelPromotionPolicyAuthorityVerification.rejected(Reason.POLICY_KEY_RETIRED);
        }
        try {
            Reason reason = checkSignedPolicy(policy, key, clock.instant(), true);
            return reason == Reason.NONE
                    ? ModelPromotionPolicyAuthorityVerification.verified()
                    : ModelPromotionPolicyAuthorityVerification.rejected(reason);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            return ModelPromotionPolicyAuthorityVerification.unavailable();
        }
    }

    private Reason checkSignedPolicy(ModelPromotionPolicy policy,
            ModelPromotionTrustedIssuerRegistry.TrustedKey key, Instant now, boolean currentAuthorization)
            throws NoSuchAlgorithmException, InvalidKeyException {
        try {
            ModelPromotionPolicy.Content content = policy.content();
            Instant approvedAt = GovernanceTimestampContract.parse(content.provenance().approvedAt(), "approvedAt");
            Instant issuedAt = ModelPromotionPolicySignatureContract.canonicalIssuedAt(
                    policy.authorizationEvidence().issuedAt());
            Instant effectiveFrom = GovernanceTimestampContract.parse(content.effectiveFromInclusive(),
                    "effectiveFromInclusive");
            Instant effectiveTo = GovernanceTimestampContract.parse(content.effectiveToExclusive(),
                    "effectiveToExclusive");
            if (issuedAt.isAfter(now.plusSeconds(MAX_FUTURE_ISSUANCE_SKEW_SECONDS))) {
                return Reason.POLICY_SIGNATURE_FUTURE_ISSUED;
            }
            if (approvedAt.isAfter(issuedAt) || issuedAt.isAfter(effectiveFrom)
                    || !effectiveFrom.isBefore(effectiveTo)) {
                return Reason.POLICY_TIME_INVALID;
            }
            if (issuedAt.isBefore(key.validFrom()) || issuedAt.isAfter(key.validUntil())) {
                return Reason.POLICY_KEY_EXPIRED;
            }
            if (currentAuthorization) {
                if (now.isBefore(key.validFrom())) {
                    return Reason.POLICY_KEY_NOT_YET_VALID;
                }
                if (now.isAfter(key.validUntil())) {
                    return Reason.POLICY_KEY_EXPIRED;
                }
                // Future-effective policies may be pre-published, but expired policies authorize no new decisions.
                if (!now.isBefore(effectiveTo)) {
                    return Reason.POLICY_EXPIRED;
                }
            } else if (key.status() == ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED
                    && now.isBefore(key.validUntil())) {
                // A retired key's validUntil is its last authorized issuance instant.
                return Reason.POLICY_TIME_INVALID;
            }
            byte[] signingInput = ModelPromotionPolicySignatureContract.verificationBytes(objectMapper, policy);
            byte[] signature = ModelPromotionPolicySignatureContract.decodeSignature(
                    policy.authorizationEvidence().signature());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key.publicKey());
            verifier.update(signingInput);
            return verifier.verify(signature) ? Reason.NONE : Reason.POLICY_SIGNATURE_INVALID;
        } catch (IllegalArgumentException exception) {
            return Reason.POLICY_CONTRACT_INVALID;
        } catch (SignatureException exception) {
            return Reason.POLICY_SIGNATURE_INVALID;
        }
    }

    private boolean institutional(ModelPromotionPolicy policy) {
        return policy != null && policy.content().provenance().authority()
                == ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED;
    }

    private boolean authorized(ModelPromotionTrustedIssuerRegistry.TrustedKey key,
            ModelPromotionPolicy.Content content) {
        return key.institutionalScopes().contains(content.governanceScope().institutionalScope())
                && key.environments().contains(content.governanceScope().environment())
                && key.intendedUses().contains(content.intendedUse());
    }
}
