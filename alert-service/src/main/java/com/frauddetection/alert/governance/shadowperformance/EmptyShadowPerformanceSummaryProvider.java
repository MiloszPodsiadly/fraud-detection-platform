package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EmptyShadowPerformanceSummaryProvider implements ShadowPerformanceSummaryProvider {

    @Override
    public Optional<ShadowPerformanceSummary> currentSummary() {
        return Optional.empty();
    }
}
