package com.frauddetection.alert.governance.shadowperformance;

import java.util.Optional;

public class EmptyShadowPerformanceSummaryProvider implements ShadowPerformanceSummaryProvider {

    @Override
    public Optional<ShadowPerformanceSummary> currentSummary() {
        return Optional.empty();
    }
}
