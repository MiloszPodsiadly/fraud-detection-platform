package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.Optional;

public interface PromotionReviewReadinessReportProvider {
    Optional<PromotionReviewReadinessReport> currentReport();
}
