package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.service.FraudCaseQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/fraud-cases/work-queue")
public class FraudCaseWorkQueueSummaryController {

    private final FraudCaseQueryService fraudCaseQueryService;
    private final AlertServiceMetrics metrics;

    public FraudCaseWorkQueueSummaryController(FraudCaseQueryService fraudCaseQueryService, AlertServiceMetrics metrics) {
        this.fraudCaseQueryService = fraudCaseQueryService;
        this.metrics = metrics;
    }

    @GetMapping("/summary")
    public FraudCaseWorkQueueSummaryResponse summary() {
        Instant startedAt = Instant.now();
        try {
            FraudCaseWorkQueueSummaryResponse response = fraudCaseQueryService.globalFraudCaseSummary();
            metrics.recordFraudCaseWorkQueueSummaryOutcome("success", Duration.between(startedAt, Instant.now()));
            return response;
        } catch (RuntimeException exception) {
            metrics.recordFraudCaseWorkQueueSummaryOutcome("failure", Duration.between(startedAt, Instant.now()));
            throw exception;
        }
    }
}
