package com.frauddetection.alert.governance.modelcard;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ModelCardReadMetrics {

    private static final String METRIC = "governance_model_card_read_total";

    private final MeterRegistry meterRegistry;

    public ModelCardReadMetrics(MeterRegistry meterRegistry) {
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
        UNAVAILABLE("unavailable"),
        INVALID("invalid");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
