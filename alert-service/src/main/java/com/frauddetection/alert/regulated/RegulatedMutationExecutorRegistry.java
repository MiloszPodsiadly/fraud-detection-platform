package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class RegulatedMutationExecutorRegistry {

    private final Map<RegulatedMutationModelVersion, RegulatedMutationExecutor> executors;

    @Autowired
    public RegulatedMutationExecutorRegistry(
            List<RegulatedMutationExecutor> executors,
            @Value("${app.regulated-mutations.evidence-gated-finalize.enabled:false}") boolean evidenceGatedFinalizeEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.submit-decision.enabled:false}") boolean submitDecisionEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.fraud-case-update.enabled:false}") boolean fraudCaseUpdateEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.trust-incident.enabled:false}") boolean trustIncidentEnabled,
            @Value("${app.regulated-mutations.evidence-gated-finalize.outbox-resolution.enabled:false}") boolean outboxResolutionEnabled
    ) {
        this(
                executors,
                evidenceGatedFinalizeEnabled && (
                        submitDecisionEnabled
                                || fraudCaseUpdateEnabled
                                || trustIncidentEnabled
                                || outboxResolutionEnabled
                )
        );
    }

    public RegulatedMutationExecutorRegistry(
            List<RegulatedMutationExecutor> executors,
            boolean evidenceGatedFinalizeActive
    ) {
        if (executors == null || executors.isEmpty()) {
            throw new IllegalStateException("Regulated mutation executor registry requires at least one executor.");
        }
        EnumMap<RegulatedMutationModelVersion, RegulatedMutationExecutor> byVersion =
                new EnumMap<>(RegulatedMutationModelVersion.class);
        for (RegulatedMutationExecutor executor : executors) {
            if (executor == null || executor.modelVersion() == null) {
                throw new IllegalStateException("Regulated mutation executor registry cannot register a null model version.");
            }
            RegulatedMutationExecutor previous = byVersion.putIfAbsent(executor.modelVersion(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate regulated mutation executor for model version "
                        + executor.modelVersion() + ".");
            }
        }
        requirePresent(byVersion, RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION);
        if (evidenceGatedFinalizeActive) {
            requirePresent(byVersion, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        }
        this.executors = Map.copyOf(byVersion);
    }

    public RegulatedMutationExecutor executorFor(RegulatedMutationCommandDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Regulated mutation command document is required.");
        }
        return executorFor(document.mutationModelVersionOrLegacy());
    }

    public RegulatedMutationExecutor executorFor(RegulatedMutationModelVersion modelVersion) {
        RegulatedMutationModelVersion resolved = modelVersion == null
                ? RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION
                : modelVersion;
        RegulatedMutationExecutor executor = executors.get(resolved);
        if (executor == null) {
            throw new IllegalStateException("No regulated mutation executor registered for model version " + resolved + ".");
        }
        return executor;
    }

    private static void requirePresent(
            Map<RegulatedMutationModelVersion, RegulatedMutationExecutor> executors,
            RegulatedMutationModelVersion modelVersion
    ) {
        if (!executors.containsKey(modelVersion)) {
            throw new IllegalStateException("Missing regulated mutation executor for model version " + modelVersion + ".");
        }
    }
}
