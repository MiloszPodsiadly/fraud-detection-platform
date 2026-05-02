package com.frauddetection.alert.trust;

import java.util.List;

public record TrustIncidentSummary(
        long openCriticalIncidentCount,
        long openHighIncidentCount,
        long unacknowledgedCriticalIncidentCount,
        Long oldestOpenIncidentAgeSeconds,
        List<String> topIncidentTypes,
        String incidentHealthStatus
) {
    public static TrustIncidentSummary empty() {
        return new TrustIncidentSummary(0L, 0L, 0L, null, List.of(), "HEALTHY");
    }
}
