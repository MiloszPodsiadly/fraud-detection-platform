package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
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
            List<RegulatedMutationExecutor> executors
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
        requirePresent(byVersion, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        this.executors = Map.copyOf(byVersion);
    }

    public RegulatedMutationExecutor executorFor(RegulatedMutationCommandDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Regulated mutation command document is required.");
        }
        RegulatedMutationExecutor executor = executorFor(document.getMutationModelVersion());
        AuditAction action = parseAction(document.getAction());
        AuditResourceType resourceType = parseResourceType(document.getResourceType());
        RegulatedMutationDefinitions.requireSupported(action, resourceType);
        if (!executor.supports(action, resourceType)) {
            throw new IllegalStateException("Executor " + executor.modelVersion()
                    + " does not support action/resource " + action + "/" + resourceType + ".");
        }
        return executor;
    }

    public RegulatedMutationExecutor executorFor(RegulatedMutationModelVersion modelVersion) {
        if (modelVersion != RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1) {
            throw new IllegalStateException("Unsupported persisted regulated mutation model version.");
        }
        RegulatedMutationExecutor executor = executors.get(modelVersion);
        if (executor == null) {
            throw new IllegalStateException("No regulated mutation executor registered for model version " + modelVersion + ".");
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

    private static AuditAction parseAction(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Regulated mutation command action is required for executor routing.");
        }
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported regulated mutation command action for executor routing.", exception);
        }
    }

    private static AuditResourceType parseResourceType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Regulated mutation command resource type is required for executor routing.");
        }
        try {
            return AuditResourceType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported regulated mutation command resource type for executor routing.", exception);
        }
    }
}
