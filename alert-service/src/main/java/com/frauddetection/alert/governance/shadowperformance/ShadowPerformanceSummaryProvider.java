package com.frauddetection.alert.governance.shadowperformance;

import java.util.Optional;

public interface ShadowPerformanceSummaryProvider {

    Optional<ShadowPerformanceSummary> currentSummary();
}
