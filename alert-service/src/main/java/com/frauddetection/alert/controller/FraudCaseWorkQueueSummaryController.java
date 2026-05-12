package com.frauddetection.alert.controller;

import com.frauddetection.alert.api.FraudCaseWorkQueueSummaryResponse;
import com.frauddetection.alert.service.FraudCaseManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud-cases/work-queue")
public class FraudCaseWorkQueueSummaryController {

    private final FraudCaseManagementService fraudCaseManagementService;

    public FraudCaseWorkQueueSummaryController(FraudCaseManagementService fraudCaseManagementService) {
        this.fraudCaseManagementService = fraudCaseManagementService;
    }

    @GetMapping("/summary")
    public FraudCaseWorkQueueSummaryResponse summary() {
        return fraudCaseManagementService.workQueueSummary();
    }
}
