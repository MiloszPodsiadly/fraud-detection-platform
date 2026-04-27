package com.frauddetection.alert.audit.external;

public record SignedAuditAnchorPayload(
        String signatureAlgorithm,
        String signature,
        String keyId
) {
}
