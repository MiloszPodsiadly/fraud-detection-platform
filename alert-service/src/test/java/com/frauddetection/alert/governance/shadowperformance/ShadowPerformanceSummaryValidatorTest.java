package com.frauddetection.alert.governance.shadowperformance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShadowPerformanceSummaryValidatorTest {

    private final ShadowPerformanceSummaryValidator validator = new ShadowPerformanceSummaryValidator();

    @Test
    void acceptsValidatedFdp105Summary() {
        assertThatCode(() -> validator.validate(validSummary())).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingEvaluationPopulation() {
        ShadowPerformanceSummary summary = replacePopulation(null);

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsInconsistentNotEvaluationEligiblePopulation() {
        ShadowPerformanceSummary summary = replacePopulation(new ShadowPerformanceSummary.ShadowPerformancePopulation(5, 3, 2));

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsDisagreementTotalGreaterThanDatasetRecordsRead() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                base.summaryType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.model(),
                base.governance(),
                base.evaluation(),
                new ShadowPerformanceSummary.ShadowPerformancePopulation(2, 1, 1),
                base.metrics(),
                base.disagreementSummary(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void excellentMetricsDoNotCreateApprovalSemantics() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                base.summaryType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.model(),
                base.governance(),
                base.evaluation(),
                base.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(1.0, 1.0, 0.0, 1, 1, 1, 1, 1, 1),
                base.disagreementSummary(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );

        assertThatCode(() -> validator.validate(summary)).doesNotThrowAnyException();
    }

    @Test
    void positiveApprovalTermsAreRejected() {
        ShadowPerformanceSummary base = validSummary();
        for (String value : List.of("PRODUCTION_APPROVED", "PROMOTION_READY", "THRESHOLD_RECOMMENDATION", "PAYMENT_AUTHORIZATION")) {
            ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                    base.summaryType(),
                    base.summaryVersion(),
                    base.generatedAt(),
                    base.model(),
                    base.governance(),
                    base.evaluation(),
                    base.evaluationPopulation(),
                    base.metrics(),
                    base.disagreementSummary(),
                    List.of(value),
                    base.limitations(),
                    base.banner()
            );

            assertThatThrownBy(() -> validator.validate(summary))
                    .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
        }
    }

    private ShadowPerformanceSummary replacePopulation(ShadowPerformanceSummary.ShadowPerformancePopulation population) {
        ShadowPerformanceSummary base = validSummary();
        return new ShadowPerformanceSummary(
                base.summaryType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.model(),
                base.governance(),
                base.evaluation(),
                population,
                base.metrics(),
                base.disagreementSummary(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );
    }

    private ShadowPerformanceSummary validSummary() {
        return new StaticShadowPerformanceSummaryProvider().currentSummary().orElseThrow();
    }
}
