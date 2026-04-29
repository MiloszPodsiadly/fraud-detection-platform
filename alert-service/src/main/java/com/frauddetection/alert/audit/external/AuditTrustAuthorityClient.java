package com.frauddetection.alert.audit.external;

import java.util.List;

public interface AuditTrustAuthorityClient {

    SignedAuditAnchorPayload sign(AuditAnchorSigningPayload payload);

    boolean verify(AuditAnchorSigningPayload payload, SignedAuditAnchorPayload signature);

    List<AuditTrustAuthorityKey> keys();

    boolean enabled();
}
