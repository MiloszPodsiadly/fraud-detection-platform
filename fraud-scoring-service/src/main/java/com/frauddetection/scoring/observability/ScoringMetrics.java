package com.frauddetection.scoring.observability;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.config.ScoringMode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class ScoringMetrics {

    private final MeterRegistry meterRegistry;

    public ScoringMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordScoringRequest(ScoringMode mode, RiskLevel riskLevel, boolean fallbackUsed, boolean success, long durationNanos) {
        String modeTag = normalize(mode);
        String outcome = success ? "success" : "failure";

        counter(
                "fraud.scoring.requests",
                "mode", modeTag,
                "outcome", outcome,
                "fallback_used", String.valueOf(fallbackUsed),
                "risk_level", normalize(riskLevel)
        ).increment();

        timer(
                "fraud.scoring.latency",
                "mode", modeTag,
                "outcome", outcome
        ).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMlClientResponse(boolean modelAvailable, long durationNanos) {
        String outcome = modelAvailable ? "available" : "unavailable";
        counter("fraud.scoring.ml.client.requests", "outcome", outcome).increment();
        timer("fraud.scoring.ml.client.latency", "outcome", outcome).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMlClientFailure(long durationNanos) {
        counter("fraud.scoring.ml.client.requests", "outcome", "error").increment();
        timer("fraud.scoring.ml.client.latency", "outcome", "error").record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordFallback(ScoringMode mode, String reason) {
        counter(
                "fraud.scoring.fallbacks",
                "mode", normalize(mode),
                "reason", normalizeReason(reason)
        ).increment();
    }

    public void recordModelDisagreement(ScoringMode mode, String signal) {
        counter(
                "fraud.scoring.ml.diagnostics.disagreements",
                "mode", normalize(mode),
                "signal", signal
        ).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }

    private String normalize(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String normalized = reason.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
