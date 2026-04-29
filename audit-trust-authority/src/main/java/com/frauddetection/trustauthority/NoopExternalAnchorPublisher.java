package com.frauddetection.trustauthority;

import org.springframework.stereotype.Component;

@Component
class NoopExternalAnchorPublisher implements ExternalAnchorPublisher {

    @Override
    public void publish(TrustAuthorityAuditHeadResponse head) {
        // FDP-23 only exposes the extension seam. Real external anchoring is FDP-24.
    }
}
