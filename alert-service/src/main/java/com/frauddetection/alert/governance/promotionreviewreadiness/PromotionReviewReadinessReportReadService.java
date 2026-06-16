package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PromotionReviewReadinessReportReadService {

    private final PromotionReviewReadinessReportProvider provider;
    private final PromotionReviewReadinessReportValidator validator;

    public PromotionReviewReadinessReportReadService(
            PromotionReviewReadinessReportProvider provider,
            PromotionReviewReadinessReportValidator validator
    ) {
        this.provider = provider;
        this.validator = validator;
    }

    public PromotionReviewReadinessReportResponse currentReport() {
        PromotionReviewReadinessReport report;
        try {
            report = provider.currentReport()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promotion review readiness report not found."));
        } catch (PromotionReviewReadinessReportProviderUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Promotion review readiness report unavailable.");
        }
        try {
            validator.validate(report);
        } catch (PromotionReviewReadinessReportValidationException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Promotion review readiness report is invalid.");
        }
        return PromotionReviewReadinessReportResponse.from(report);
    }
}
