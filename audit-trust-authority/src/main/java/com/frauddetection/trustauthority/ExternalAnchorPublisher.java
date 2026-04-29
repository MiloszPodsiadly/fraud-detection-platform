package com.frauddetection.trustauthority;

interface ExternalAnchorPublisher {

    void publish(TrustAuthorityAuditHeadResponse head);
}
