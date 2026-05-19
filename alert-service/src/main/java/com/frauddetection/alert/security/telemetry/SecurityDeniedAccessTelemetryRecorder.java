package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class SecurityDeniedAccessTelemetryRecorder {

    public static final String DENIED_ACCESS_METRIC = "fraud.security.access.denied";

    private static final Logger log = LoggerFactory.getLogger(SecurityDeniedAccessTelemetryRecorder.class);

    private final MeterRegistry meterRegistry;

    public SecurityDeniedAccessTelemetryRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    public void record(SecurityDeniedAccessSnapshot snapshot) {
        SecurityDeniedAccessSnapshot bounded = snapshot == null
                ? new SecurityDeniedAccessSnapshot("unknown", "unauthorized", "OTHER", "unknown")
                : snapshot;
        try {
            Counter.builder(DENIED_ACCESS_METRIC)
                    .tags(bounded.metricTags())
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException exception) {
            log.warn(
                    "Security denied-access telemetry recording failed routeGroup={} outcome={} method={} authState={}",
                    bounded.routeGroup(),
                    bounded.outcome(),
                    bounded.method(),
                    bounded.authState()
            );
        }
    }
}
