package com.frauddetection.alert.suspicious.api.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MicrometerLinkedAlertContextMetricsRecorder implements LinkedAlertContextMetricsRecorder {

    public static final String METRIC_NAME = "fraud.suspicious_transaction.linked_alert.read";
    public static final String ENDPOINT_LABEL = "linked_alert_context";

    private static final Logger log = LoggerFactory.getLogger(MicrometerLinkedAlertContextMetricsRecorder.class);

    private final MeterRegistry meterRegistry;

    public MicrometerLinkedAlertContextMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    @Override
    public void record(LinkedAlertContextMetricOutcome outcome) {
        LinkedAlertContextMetricOutcome boundedOutcome = outcome == null
                ? LinkedAlertContextMetricOutcome.ERROR
                : outcome;
        try {
            Counter.builder(METRIC_NAME)
                    .tag("endpoint", ENDPOINT_LABEL)
                    .tag("outcome", boundedOutcome.label())
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException exception) {
            log.warn("Linked alert context metric recording failed outcome={}", boundedOutcome.label());
        }
    }
}
