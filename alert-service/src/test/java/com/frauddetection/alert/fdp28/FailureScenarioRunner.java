package com.frauddetection.alert.fdp28;

import java.util.EnumMap;
import java.util.Map;

public final class FailureScenarioRunner {

    private final Map<FailureInjectionPoint, RuntimeException> failures =
            new EnumMap<>(FailureInjectionPoint.class);

    public FailureScenarioRunner failAt(FailureInjectionPoint point, RuntimeException exception) {
        failures.put(point, exception);
        return this;
    }

    public void run(FailureInjectionPoint point) {
        RuntimeException failure = failures.get(point);
        if (failure != null) {
            throw failure;
        }
    }
}
