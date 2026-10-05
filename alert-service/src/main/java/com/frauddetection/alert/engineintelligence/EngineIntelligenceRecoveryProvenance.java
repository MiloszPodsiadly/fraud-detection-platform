package com.frauddetection.alert.engineintelligence;

import java.util.Objects;

public record EngineIntelligenceRecoveryProvenance(
        int contractVersion,
        String deadLetterTopic,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        String sourceConsumerGroup
) {
    public static final int CONTRACT_VERSION = 1;

    public EngineIntelligenceRecoveryProvenance {
        if (contractVersion != CONTRACT_VERSION
                || deadLetterTopic == null || deadLetterTopic.isBlank()
                || sourceTopic == null || sourceTopic.isBlank()
                || sourcePartition < 0
                || sourceOffset < 0
                || sourceConsumerGroup == null || sourceConsumerGroup.isBlank()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_RECOVERY_PROVENANCE_INVALID");
        }
        deadLetterTopic = Objects.requireNonNull(deadLetterTopic).trim();
        sourceTopic = Objects.requireNonNull(sourceTopic).trim();
        sourceConsumerGroup = Objects.requireNonNull(sourceConsumerGroup).trim();
    }
}
