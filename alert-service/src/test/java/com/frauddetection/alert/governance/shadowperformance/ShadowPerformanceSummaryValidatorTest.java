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
    void rejectsAnyMissingDiagnosticNonGoal() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = replaceGovernance(new ShadowPerformanceSummary.ShadowPerformanceGovernance(
                base.governance().governanceStatus(),
                base.governance().diagnosticOnly(),
                false,
                base.governance().notPromotionApproval(),
                base.governance().notThresholdRecommendation(),
                base.governance().notPaymentAuthorization(),
                base.governance().notAutomaticDecisioning()
        ));

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void acceptsIsoInstantGeneratedAt() {
        assertThatCode(() -> validator.validate(replaceGeneratedAt("2026-06-13T02:00:00Z")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNonIsoGeneratedAt() {
        assertThatThrownBy(() -> validator.validate(replaceGeneratedAt("2026-06-08 02:00:00")))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsBlankGeneratedAt() {
        assertThatThrownBy(() -> validator.validate(replaceGeneratedAt(" ")))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsUnsafeGeneratedAt() {
        assertThatThrownBy(() -> validator.validate(replaceGeneratedAt("token://2026-06-08T02:00:00Z")))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsSummaryGeneratedBeforeEvaluationCard() {
        assertThatThrownBy(() -> validator.validate(replaceGeneratedAt("2026-06-11T23:59:59Z")))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsMissingEvaluationPopulation() {
        ShadowPerformanceSummary summary = replacePopulation(null);

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsInconsistentNotEvaluationEligiblePopulation() {
        ShadowPerformanceSummary summary = replacePopulation(new ShadowPerformanceSummary.ShadowPerformancePopulation(5, 4, 2));

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsPopulationOutsideBound() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                base.reportType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.evaluationSubject(),
                base.metricBasis(),
                base.governance(),
                base.evaluation(),
                new ShadowPerformanceSummary.ShadowPerformancePopulation(1_001, 500, 501),
                base.metrics(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void rejectsUnsupportedLineageVersions() {
        ShadowPerformanceSummary base = validSummary();
        for (ShadowPerformanceSummary.ShadowPerformanceEvaluation evaluation : List.of(
                replaceEvaluationLineage(base.evaluation(), "other-artifact-format-v99", "feedback-dataset-v1", "FEEDBACK_CREATED_AT"),
                replaceEvaluationLineage(base.evaluation(), "fdp123-report-artifact-set-v1", "unknown-dataset-v77", "FEEDBACK_CREATED_AT"),
                replaceEvaluationLineage(base.evaluation(), "fdp123-report-artifact-set-v1", "feedback-dataset-v1", "TRANSACTION_CREATED_AT")
        )) {
            ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                    base.reportType(),
                    base.summaryVersion(),
                    base.generatedAt(),
                    base.evaluationSubject(),
                    base.metricBasis(),
                    base.governance(),
                    evaluation,
                    base.evaluationPopulation(),
                    base.metrics(),
                    base.warnings(),
                    base.limitations(),
                    base.banner()
            );

            assertThatThrownBy(() -> validator.validate(summary))
                    .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
        }
    }

    @Test
    void rejectsTwentyOneLimitations() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                base.reportType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.evaluationSubject(),
                base.metricBasis(),
                base.governance(),
                base.evaluation(),
                base.evaluationPopulation(),
                base.metrics(),
                base.warnings(),
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(index -> "LIMITATION_" + index)
                        .toList(),
                base.banner()
        );

        assertThatThrownBy(() -> validator.validate(summary))
                .isInstanceOf(ShadowPerformanceSummaryValidationException.class);
    }

    @Test
    void excellentMetricsDoNotCreateApprovalSemantics() {
        ShadowPerformanceSummary base = validSummary();
        ShadowPerformanceSummary summary = new ShadowPerformanceSummary(
                base.reportType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.evaluationSubject(),
                base.metricBasis(),
                base.governance(),
                base.evaluation(),
                base.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        ShadowPerformanceSummaryTestFixtures.metric(1.0),
                        ShadowPerformanceSummaryTestFixtures.metric(1.0),
                        ShadowPerformanceSummaryTestFixtures.metric(0.0),
                        ShadowPerformanceSummaryTestFixtures.metric(0.0)
                ),
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
                    base.reportType(),
                    base.summaryVersion(),
                    base.generatedAt(),
                    base.evaluationSubject(),
                    base.metricBasis(),
                    base.governance(),
                    base.evaluation(),
                    base.evaluationPopulation(),
                    base.metrics(),
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
                base.reportType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.evaluationSubject(),
                base.metricBasis(),
                base.governance(),
                base.evaluation(),
                population,
                base.metrics(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );
    }

    private ShadowPerformanceSummary replaceGovernance(ShadowPerformanceSummary.ShadowPerformanceGovernance governance) {
        ShadowPerformanceSummary base = validSummary();
        return new ShadowPerformanceSummary(
                base.reportType(),
                base.summaryVersion(),
                base.generatedAt(),
                base.evaluationSubject(),
                base.metricBasis(),
                governance,
                base.evaluation(),
                base.evaluationPopulation(),
                base.metrics(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );
    }

    private ShadowPerformanceSummary replaceGeneratedAt(String generatedAt) {
        ShadowPerformanceSummary base = validSummary();
        return new ShadowPerformanceSummary(
                base.reportType(),
                base.summaryVersion(),
                generatedAt,
                base.evaluationSubject(),
                base.metricBasis(),
                base.governance(),
                base.evaluation(),
                base.evaluationPopulation(),
                base.metrics(),
                base.warnings(),
                base.limitations(),
                base.banner()
        );
    }

    private ShadowPerformanceSummary validSummary() {
        return ShadowPerformanceSummaryTestFixtures.validSummary();
    }

    private ShadowPerformanceSummary.ShadowPerformanceEvaluation replaceEvaluationLineage(
            ShadowPerformanceSummary.ShadowPerformanceEvaluation base,
            String evaluationArtifactSetVersion,
            String datasetVersion,
            String datasetTimeBasis
    ) {
        return new ShadowPerformanceSummary.ShadowPerformanceEvaluation(
                base.evaluationCardType(),
                base.evaluationCardVersion(),
                base.evaluationPurpose(),
                base.evaluationReportType(),
                base.evaluationReportVersion(),
                base.evaluationReportGeneratedAt(),
                base.evaluationCardGeneratedAt(),
                evaluationArtifactSetVersion,
                datasetVersion,
                datasetTimeBasis,
                base.sourceManifestSha256(),
                base.sourceEvaluationCardManifestSha256()
        );
    }
}
