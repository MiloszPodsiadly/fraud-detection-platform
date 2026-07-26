package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class PromotionReviewReadinessReportValidatorTest {

    private final PromotionReviewReadinessReportValidator validator = new PromotionReviewReadinessReportValidator();

    @Test
    void acceptsCanonicalValidReport() {
        assertThatCode(() -> validator.validate(PromotionReviewReadinessReportTestFixtures.validReport()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLegacyWarnAndNotApplicableCheckStatuses() {
        for (String status : List.of("WARN", "NOT_APPLICABLE")) {
            PromotionReviewReadinessReport report = withChecks(
                    PromotionReviewReadinessReportTestFixtures.validReport(),
                    replaceCheckStatus(PromotionReviewReadinessReportTestFixtures.validReport().checks(), 0, status)
            );

            assertThatCode(() -> validator.validate(report))
                    .isInstanceOf(PromotionReviewReadinessReportValidationException.class);
        }
    }

    @Test
    void rejectsChecksThatDoNotMatchImmutableCheckInputs() {
        PromotionReviewReadinessReport report = withChecks(
                PromotionReviewReadinessReportTestFixtures.validReport(),
                replaceCheckStatus(PromotionReviewReadinessReportTestFixtures.validReport().checks(), 0, "FAIL")
        );

        assertThatCode(() -> validator.validate(report))
                .isInstanceOf(PromotionReviewReadinessReportValidationException.class)
                .hasMessage("checks must match checkInputs");
    }

    @Test
    void nonMinimumFailureTakesPrecedenceOverInsufficientEvidence() {
        PromotionReviewReadinessReport valid = PromotionReviewReadinessReportTestFixtures.validReport();
        PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs =
                new PromotionReviewReadinessReport.PromotionReviewReadinessInputs(
                        valid.inputs().shadowPerformanceSummary(),
                        4,
                        valid.inputs().recordsEvaluated()
                );
        PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs checkInputs =
                new PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs(
                        valid.checkInputs().sourceShadowSummaryManifestSha256(),
                        valid.checkInputs().shadowPerformanceSummary(),
                        valid.checkInputs().governance(),
                        new PromotionReviewReadinessReport.PromotionReadinessEvaluationCheckInput(
                                valid.checkInputs().evaluation().evaluationCardType(),
                                "legacy-card",
                                valid.checkInputs().evaluation().evaluationReportType()
                        ),
                        valid.checkInputs().metricBasis(),
                        4,
                        valid.checkInputs().recordsEvaluated(),
                        valid.checkInputs().metrics()
                );
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks = replaceCheckStatus(
                replaceCheckStatus(valid.checks(), 3, "FAIL"),
                12,
                "FAIL"
        );
        PromotionReviewReadinessReport report = report(
                valid,
                "NOT_REVIEWABLE",
                inputs,
                checkInputs,
                checks,
                List.of("EVALUATION_CARD_VERSION_SUPPORTED_FAILED", "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS_FAILED")
        );

        assertThatCode(() -> validator.validate(report)).doesNotThrowAnyException();
    }

    private PromotionReviewReadinessReport withChecks(
            PromotionReviewReadinessReport source,
            List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks
    ) {
        return report(source, source.readinessStatus(), source.inputs(), source.checkInputs(), checks, source.reasonCodes());
    }

    private PromotionReviewReadinessReport report(
            PromotionReviewReadinessReport source,
            String readinessStatus,
            PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs,
            PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs checkInputs,
            List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks,
            List<String> reasonCodes
    ) {
        return new PromotionReviewReadinessReport(
                source.reportType(),
                source.reportVersion(),
                source.generatedAt(),
                source.governanceStatus(),
                readinessStatus,
                source.diagnosticOnly(),
                source.notPromotionApproval(),
                source.notThresholdRecommendation(),
                source.notProductionDecisioning(),
                source.notPaymentAuthorization(),
                source.notAutomaticDecisioning(),
                source.notAnalystRecommendation(),
                inputs,
                checkInputs,
                checks,
                reasonCodes,
                source.warnings(),
                source.limitations(),
                source.banner()
        );
    }

    private List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> replaceCheckStatus(
            List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> source,
            int index,
            String status
    ) {
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks = new ArrayList<>(source);
        PromotionReviewReadinessReport.PromotionReviewReadinessCheck check = checks.get(index);
        checks.set(index, new PromotionReviewReadinessReport.PromotionReviewReadinessCheck(
                check.name(),
                status,
                check.severity()
        ));
        return List.copyOf(checks);
    }
}
