package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.regex.Pattern;

public record ModelPromotionOperationContext(
        String operationId,
        String actorId,
        String authorizationFingerprint,
        Instant requestedAt,
        ModelPromotionActorIdentity actorIdentity
) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:@-]{1,128}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public ModelPromotionOperationContext(String operationId, String actorId, String authorizationFingerprint,
            Instant requestedAt) {
        this(operationId, actorId, authorizationFingerprint, requestedAt, null);
    }

    public ModelPromotionOperationContext {
        if (operationId == null || !IDENTIFIER.matcher(operationId).matches()) {
            throw new IllegalArgumentException("operationId is invalid");
        }
        if (actorId == null || !IDENTIFIER.matcher(actorId).matches()) {
            throw new IllegalArgumentException("actorId is invalid");
        }
        if (authorizationFingerprint == null || !SHA256.matcher(authorizationFingerprint).matches()) {
            throw new IllegalArgumentException("authorizationFingerprint must be lowercase SHA-256");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt is required");
        }
        if (actorIdentity != null && !actorId.equals(actorIdentity.actorId())) {
            throw new IllegalArgumentException("actorId must match the durable actor identity");
        }
    }
}
