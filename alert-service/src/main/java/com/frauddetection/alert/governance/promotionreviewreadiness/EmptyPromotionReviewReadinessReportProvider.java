package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.Optional;

public class EmptyPromotionReviewReadinessReportProvider implements PromotionReviewReadinessReportProvider {

    @Override
    public Optional<PromotionReviewReadinessReport> currentReport() {
        return Optional.empty();
    }
}
