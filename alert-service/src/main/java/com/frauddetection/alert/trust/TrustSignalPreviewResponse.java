package com.frauddetection.alert.trust;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TrustSignalPreviewResponse(
        boolean preview,
        @JsonProperty("signal_count")
        int signalCount,
        List<TrustSignal> signals
) {
    public static TrustSignalPreviewResponse from(List<TrustSignal> signals) {
        List<TrustSignal> safeSignals = signals == null ? List.of() : List.copyOf(signals);
        return new TrustSignalPreviewResponse(true, safeSignals.size(), safeSignals);
    }
}
