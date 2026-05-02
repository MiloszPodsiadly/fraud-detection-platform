package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

public final class RegulatedMutationIntentHasher {

    private RegulatedMutationIntentHasher() {
    }

    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    public static RegulatedMutationIntent submitDecision(
            String resourceId,
            String actorId,
            Object decision,
            String reason,
            Iterable<?> tags
    ) {
        String decisionValue = canonicalValue(decision);
        String reasonHash = hash(reason);
        String tagsHash = hash(tags);
        String action = AuditAction.SUBMIT_ANALYST_DECISION.name();
        String intentHash = hash("resourceId=" + canonicalValue(resourceId)
                + "|action=" + action
                + "|actorId=" + canonicalValue(actorId)
                + "|decision=" + decisionValue
                + "|reasonHash=" + reasonHash
                + "|tagsHash=" + tagsHash);
        return new RegulatedMutationIntent(
                intentHash,
                resourceId,
                action,
                actorId,
                decisionValue,
                reasonHash,
                tagsHash
        );
    }

    public static RegulatedMutationIntent fraudCaseUpdate(
            String caseId,
            String actorId,
            Object status,
            String assignee,
            String notes,
            Iterable<?> tags,
            Object payload
    ) {
        String statusValue = canonicalValue(status);
        String assigneeHash = hash(assignee);
        String notesHash = hash(notes);
        String tagsHash = hash(tags);
        String payloadHash = hash(payload);
        String action = AuditAction.UPDATE_FRAUD_CASE.name();
        String intentHash = hash("resourceId=" + canonicalValue(caseId)
                + "|action=" + action
                + "|actorId=" + canonicalValue(actorId)
                + "|status=" + statusValue
                + "|assigneeHash=" + assigneeHash
                + "|notesHash=" + notesHash
                + "|tagsHash=" + tagsHash
                + "|payloadHash=" + payloadHash);
        return new RegulatedMutationIntent(
                intentHash,
                caseId,
                action,
                actorId,
                null,
                null,
                tagsHash,
                statusValue,
                assigneeHash,
                notesHash,
                payloadHash
        );
    }

    public static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> canonicalValue(entry.getKey()) + ":" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object current : iterable) {
                if (!first) {
                    builder.append(",");
                }
                builder.append(canonicalValue(current));
                first = false;
            }
            return builder.append("]").toString();
        }
        return String.valueOf(value);
    }
}
