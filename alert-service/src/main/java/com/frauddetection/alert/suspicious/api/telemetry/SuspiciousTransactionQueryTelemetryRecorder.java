package com.frauddetection.alert.suspicious.api.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class SuspiciousTransactionQueryTelemetryRecorder implements SuspiciousTransactionQueryTelemetrySink {

    static final String QUERY_METRIC = "fraud.suspicious_transaction.api.query";
    static final Duration DEFAULT_SLOW_QUERY_THRESHOLD = Duration.ofMillis(500);
    static final Duration MIN_SLOW_QUERY_THRESHOLD = Duration.ofMillis(50);

    private static final Logger log = LoggerFactory.getLogger(SuspiciousTransactionQueryTelemetryRecorder.class);

    private final MeterRegistry meterRegistry;
    private final Duration slowQueryThreshold;

    public SuspiciousTransactionQueryTelemetryRecorder(
            MeterRegistry meterRegistry,
            @Value("${fraud.suspicious-transaction.telemetry.slow-query-threshold:500ms}") Duration slowQueryThreshold
    ) {
        this.meterRegistry = meterRegistry;
        this.slowQueryThreshold = normalizedThreshold(slowQueryThreshold);
    }

    @Override
    public void record(SuspiciousTransactionQueryTelemetrySnapshot snapshot) {
        try {
            SuspiciousTransactionQueryTelemetrySnapshot bounded = snapshot == null
                    ? new SuspiciousTransactionQueryTelemetrySnapshot(
                    "search", "error", "unknown", "0", "unknown", "unknown", "unknown", "500ms_plus", Duration.ZERO)
                    : snapshot;
            Timer.builder(QUERY_METRIC)
                    .tags(bounded.metricTags())
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(bounded.duration());
            if (!bounded.duration().minus(slowQueryThreshold).isNegative()) {
                log.warn(
                        "SuspiciousTransaction query telemetry slow_query endpoint={} outcome={} queryShape={} filterCountBucket={} resultSizeBucket={} hasNext={} cursorUsed={} durationBucket={}",
                        bounded.endpoint(),
                        bounded.outcome(),
                        bounded.queryShape(),
                        bounded.filterCountBucket(),
                        bounded.resultSizeBucket(),
                        bounded.hasNext(),
                        bounded.cursorUsed(),
                        bounded.durationBucket()
                );
            }
        } catch (RuntimeException exception) {
            log.warn("SuspiciousTransaction query telemetry recording failed endpoint=unknown outcome=error queryShape=unknown");
        }
    }

    Duration slowQueryThreshold() {
        return slowQueryThreshold;
    }

    static Duration normalizedThreshold(Duration threshold) {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            return DEFAULT_SLOW_QUERY_THRESHOLD;
        }
        if (threshold.compareTo(MIN_SLOW_QUERY_THRESHOLD) < 0) {
            return MIN_SLOW_QUERY_THRESHOLD;
        }
        return threshold;
    }
}
