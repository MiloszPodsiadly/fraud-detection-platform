package com.frauddetection.enricher.domain;

import java.util.Map;

public record EnrichedTransactionFeatures(
        Map<String, Object> featureSnapshot
) {
}
