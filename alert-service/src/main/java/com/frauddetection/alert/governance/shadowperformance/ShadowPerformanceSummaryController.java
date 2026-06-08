package com.frauddetection.alert.governance.shadowperformance;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/shadow-performance/summary")
public class ShadowPerformanceSummaryController {

    private final ShadowPerformanceSummaryReadService readService;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public ShadowPerformanceSummaryController(
            ShadowPerformanceSummaryReadService readService,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.readService = readService;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping("/current")
    @AuditedSensitiveRead
    public ShadowPerformanceSummaryResponse currentSummary(HttpServletRequest request) {
        ShadowPerformanceSummaryResponse response = readService.currentSummary();
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.SHADOW_PERFORMANCE_SUMMARY,
                ReadAccessResourceType.SHADOW_PERFORMANCE_SUMMARY,
                null,
                1,
                request
        );
        return response;
    }
}
