package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/fraud-cases/work-queue")
public class FraudCaseWorkQueueSummaryController {

    private final FraudCaseQueryService fraudCaseQueryService;
    private final AlertServiceMetrics metrics;
    private final Clock clock;

    @Autowired
    public FraudCaseWorkQueueSummaryController(FraudCaseQueryService fraudCaseQueryService, AlertServiceMetrics metrics) {
        this(fraudCaseQueryService, metrics, Clock.systemUTC());
    }

    FraudCaseWorkQueueSummaryController(FraudCaseQueryService fraudCaseQueryService, AlertServiceMetrics metrics, Clock clock) {
        this.fraudCaseQueryService = fraudCaseQueryService;
        this.metrics = metrics;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @GetMapping("/summary")
    public FraudCaseWorkQueueSummaryResponse summary() {
        Instant startedAt = clock.instant();
        try {
            FraudCaseWorkQueueSummaryResponse response = fraudCaseQueryService.globalFraudCaseSummary();
            metrics.recordFraudCaseWorkQueueSummaryOutcome("success", Duration.between(startedAt, clock.instant()));
            return response;
        } catch (RuntimeException exception) {
            metrics.recordFraudCaseWorkQueueSummaryOutcome("failure", Duration.between(startedAt, clock.instant()));
            throw exception;
        }
    }
}
