package com.frauddetection.alert.audit.external;

import org.springframework.stereotype.Component;

@Component
class NoopAuditAnchorSigner implements AuditAnchorSigner {

    @Override
    public SignedAuditAnchorPayload sign(String canonicalPayload) {
        return SignedAuditAnchorPayload.unsigned();
    }
}
