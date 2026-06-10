package com.frauddetection.alert.governance.shadowperformance;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/shadow-performance/summary")
public class ShadowPerformanceSummaryController {

    private final ShadowPerformanceSummaryReadService readService;

    public ShadowPerformanceSummaryController(ShadowPerformanceSummaryReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/current")
    @AuditedSensitiveRead
    public ShadowPerformanceSummaryResponse currentSummary() {
        return readService.currentSummary();
    }
}
