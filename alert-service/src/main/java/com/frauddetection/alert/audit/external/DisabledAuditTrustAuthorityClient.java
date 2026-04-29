package com.frauddetection.alert.audit.external;

import java.util.List;

class DisabledAuditTrustAuthorityClient implements AuditTrustAuthorityClient {

    @Override
    public SignedAuditAnchorPayload sign(AuditAnchorSigningPayload payload) {
        return SignedAuditAnchorPayload.unsigned();
    }

    @Override
    public boolean verify(AuditAnchorSigningPayload payload, SignedAuditAnchorPayload signature) {
        return false;
    }

    @Override
    public List<AuditTrustAuthorityKey> keys() {
        return List.of();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
