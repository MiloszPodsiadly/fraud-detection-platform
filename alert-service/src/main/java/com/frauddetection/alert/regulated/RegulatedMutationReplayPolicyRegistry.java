package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RegulatedMutationReplayPolicyRegistry {

    private final Map<RegulatedMutationModelVersion, RegulatedMutationReplayPolicy> policies;

    @Autowired
    public RegulatedMutationReplayPolicyRegistry(
            List<RegulatedMutationReplayPolicy> policies,
            @Value("${app.regulated-mutations.evidence-gated-finalize.enabled:false}") boolean evidenceGatedFinalizeEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled:false}") boolean submitDecisionEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.fraud-case-update.enabled:false}") boolean fraudCaseUpdateEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.trust-incident.enabled:false}") boolean trustIncidentEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.outbox-resolution.enabled:false}") boolean outboxResolutionEnabled
    ) {
        this(
                policies,
                evidenceGatedFinalizeEnabled && (
                        submitDecisionEnabled
                                || fraudCaseUpdateEnabled
                                || trustIncidentEnabled
                                || outboxResolutionEnabled
                )
        );
    }

    public RegulatedMutationReplayPolicyRegistry(
            List<RegulatedMutationReplayPolicy> policies,
            boolean evidenceGatedFinalizeActive
    ) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalStateException("Regulated mutation replay policy registry requires at least one policy.");
        }
        EnumMap<RegulatedMutationModelVersion, RegulatedMutationReplayPolicy> byVersion =
                new EnumMap<>(RegulatedMutationModelVersion.class);
        for (RegulatedMutationReplayPolicy policy : policies) {
            if (policy == null || policy.modelVersion() == null) {
                throw new IllegalStateException("Regulated mutation replay policy registry cannot register a null model version.");
            }
            RegulatedMutationReplayPolicy previous = byVersion.putIfAbsent(policy.modelVersion(), policy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate regulated mutation replay policy for model version "
                        + policy.modelVersion() + ".");
            }
        }
        requirePresent(byVersion, RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        if (evidenceGatedFinalizeActive) {
            requirePresent(byVersion, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        }
        this.policies = Map.copyOf(byVersion);
    }

    public RegulatedMutationReplayDecision resolve(RegulatedMutationCommandDocument document, Instant now) {
        if (document == null) {
            throw new IllegalArgumentException("Regulated mutation command document is required.");
        }
        return policyFor(document.mutationModelVersionOrLegacy()).resolve(document, now);
    }

    public RegulatedMutationReplayPolicy policyFor(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationModelVersion resolved = modelVersion == null
                ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
                : modelVersion;
        RegulatedMutationReplayPolicy policy = policies.get(resolved);
        if (policy == null) {
            throw new IllegalStateException("No regulated mutation replay policy registered for model version " + resolved + ".");
        }
        return policy;
    }

    private static void requirePresent(
            Map<RegulatedMutationModelVersion, RegulatedMutationReplayPolicy> policies,
            RegulatedMutationModelVersion modelVersion
    ) {
        if (!policies.containsKey(modelVersion)) {
            throw new IllegalStateException("Missing regulated mutation replay policy for model version " + modelVersion + ".");
        }
    }
}
