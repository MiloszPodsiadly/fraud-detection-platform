package com.frauddetection.alert.regulated;

public record RegulatedMutationIntent(
        String intentHash,
        String resourceId,
        String action,
        String actorId,
        String decision,
        String reasonHash,
        String tagsHash,
        String status,
        String assigneeHash,
        String notesHash,
        String payloadHash
) {
    public RegulatedMutationIntent(
            String intentHash,
            String resourceId,
            String action,
            String actorId,
            String decision,
            String reasonHash,
            String tagsHash
    ) {
        this(intentHash, resourceId, action, actorId, decision, reasonHash, tagsHash, null, null, null, null);
    }
}
