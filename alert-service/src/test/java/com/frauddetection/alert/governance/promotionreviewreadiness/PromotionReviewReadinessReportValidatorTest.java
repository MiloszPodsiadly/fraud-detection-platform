package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class PromotionReviewReadinessReportValidatorTest {

    private final PromotionReviewReadinessReportValidator validator = new PromotionReviewReadinessReportValidator();

    @Test
    void acceptsCanonicalValidReport() {
        assertThatCode(() -> validator.validate(PromotionReviewReadinessReportTestFixtures.validReport()))
                .doesNotThrowAnyException();
    }
}
