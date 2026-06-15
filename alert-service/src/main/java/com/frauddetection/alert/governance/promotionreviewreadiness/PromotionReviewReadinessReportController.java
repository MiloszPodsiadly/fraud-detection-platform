package com.frauddetection.alert.governance.promotionreviewreadiness;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/governance/promotion-review-readiness")
public class PromotionReviewReadinessReportController {

    private final PromotionReviewReadinessReportReadService readService;

    public PromotionReviewReadinessReportController(PromotionReviewReadinessReportReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/current")
    @AuditedSensitiveRead
    public PromotionReviewReadinessReportResponse currentReport() {
        return readService.currentReport();
    }
}
