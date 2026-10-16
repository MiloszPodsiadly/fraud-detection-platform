package com.frauddetection.alert.governance.exactmodelshadowperformance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ExactModelShadowPerformanceReadMetrics {

    private static final String METRIC = "governance_exact_model_shadow_performance_read_total";

    private final MeterRegistry meterRegistry;

    public ExactModelShadowPerformanceReadMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    void record(Outcome outcome) {
        Counter.builder(METRIC)
                .tag("outcome", outcome.tagValue)
                .register(meterRegistry)
                .increment();
    }

    enum Outcome {
        SUCCESS("success"),
        NOT_FOUND("not_found"),
        UNAVAILABLE("unavailable");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
