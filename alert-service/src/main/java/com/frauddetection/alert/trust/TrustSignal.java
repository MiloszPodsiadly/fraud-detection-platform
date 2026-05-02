package com.frauddetection.alert.trust;

import java.util.List;

public record TrustSignal(
        String type,
        TrustIncidentSeverity severity,
        String source,
        String fingerprint,
        List<String> evidenceRefs
) {
}
