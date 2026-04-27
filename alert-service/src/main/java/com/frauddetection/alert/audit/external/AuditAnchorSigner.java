package com.frauddetection.alert.audit.external;

public interface AuditAnchorSigner {

    SignedAuditAnchorPayload sign(String canonicalPayload);
}
