package com.frauddetection.alert.regulated;

public record RegulatedMutationIntent(
        String intentHash,
        String resourceId,
        String action,
        String actorId,
        String decision,
        String reasonHash,
        String tagsHash
) {
}
