package com.frauddetection.alert.governance.shadowperformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArtifactBackedShadowPerformanceSummaryProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShadowPerformanceSummaryValidator validator = spy(new ShadowPerformanceSummaryValidator());

    @TempDir
    Path tempDir;

    @Test
    void returnsCurrentSummaryFromConfiguredArtifact() throws Exception {
        Path artifact = writeSummary(validSummary());

        Optional<ShadowPerformanceSummary> result = provider(artifact).currentSummary();

        assertThat(result).contains(validSummary());
    }

    @Test
    void validatesSummaryBeforeReturning() throws Exception {
        ShadowPerformanceSummary summary = validSummary();
        Path artifact = writeSummary(summary);

        Optional<ShadowPerformanceSummary> result = provider(artifact).currentSummary();

        assertThat(result).contains(summary);
        verify(validator).validate(summary);
    }

    @Test
    void doesNotModifySummary() throws Exception {
        ShadowPerformanceSummary summary = summaryWithMetrics(0.5, 0.25, 0.0);
        Path artifact = writeSummary(summary);

        Optional<ShadowPerformanceSummary> result = provider(artifact).currentSummary();

        assertThat(result).contains(summary);
    }

    @Test
    void doesNotRecomputeMetrics() throws Exception {
        ShadowPerformanceSummary summary = summaryWithMetrics(1.0, 1.0, 0.0);
        Path artifact = writeSummary(summary);

        ShadowPerformanceSummary result = provider(artifact).currentSummary().orElseThrow();

        assertThat(result.metrics().precisionAtBudget()).isEqualTo(1.0);
        assertThat(result.metrics().recallAtTopK()).isEqualTo(1.0);
        assertThat(result.metrics().falsePositiveRate()).isEqualTo(0.0);
        assertThat(result.disagreementSummary()).isEqualTo(summary.disagreementSummary());
    }

    @Test
    void doesNotRepairInvalidSummary() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotCoerceInvalidMetrics() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("\"precisionAtBudget\":0.666667", "\"precisionAtBudget\":\"0.666667\""));

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotDropInvalidFieldsSilently() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("\"summaryType\":\"SHADOW_PERFORMANCE_SUMMARY_V1\"",
                "\"summaryType\":\"SHADOW_PERFORMANCE_SUMMARY_V1\",\"rawPayload\":\"secret\""));

        assertUnavailable(provider(artifact));
    }

    @Test
    void returnsEmptyWhenProviderDisabled() {
        assertThat(provider(false, tempDir.resolve("current-summary.json")).currentSummary()).isEmpty();
    }

    @Test
    void returnsEmptyWhenCurrentSummaryPathNotConfigured() {
        assertThat(provider(true, null).currentSummary()).isEmpty();
        assertThat(provider(true, "   ", 1_048_576L).currentSummary()).isEmpty();
    }

    @Test
    void throwsUnavailableWhenProviderEnabledAndConfiguredArtifactMissing() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotExposeConfiguredPathWhenArtifactUnavailable() {
        Path missingArtifact = tempDir.resolve("secret-current-summary.json");

        assertThatThrownBy(provider(missingArtifact)::currentSummary)
                .isInstanceOf(ShadowPerformanceSummaryProviderUnavailableException.class)
                .hasMessage("Current shadow performance summary artifact unavailable.")
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("secret-current-summary.json");
    }

    @Test
    void doesNotFallbackToStaticSummary() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotFallbackToSampleSummary() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotFabricateZeroMetrics() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotReturnEmptySummaryObject() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void throwsUnavailableWhenConfiguredSourceUnreadable() {
        ArtifactBackedShadowPerformanceSummaryProvider provider = provider(true, "\u0000", 1_048_576L);

        assertUnavailable(provider);
    }

    @Test
    void throwsUnavailableWhenConfiguredSourceIsDirectory() {
        assertUnavailable(provider(tempDir));
    }

    @Test
    void throwsUnavailableWhenConfiguredSourceIsTooLarge() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson());

        assertUnavailable(provider(true, artifact, 16));
    }

    @Test
    void throwsUnavailableWhenMalformedJson() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, "{");

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenSummaryFailsValidation() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenUnsupportedSummaryType() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("SHADOW_PERFORMANCE_SUMMARY_V1", "MODEL_CARD"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenUnsupportedSummaryVersion() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("\"summaryVersion\":\"1.0\"", "\"summaryVersion\":\"2.0\""));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenRawIdentifiersPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("python-logistic-fraud-model", "txnref-secret"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenProductionApprovalFieldPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("DIAGNOSTIC_ONLY", "PRODUCTION_APPROVED"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenPromotionReadinessFieldPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("DIAGNOSTIC_ONLY", "PROMOTION_READY"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenThresholdRecommendationFieldPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("DIAGNOSTIC_ONLY", "THRESHOLD_RECOMMENDATION"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenPaymentAuthorizationFieldPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("DIAGNOSTIC_ONLY", "PAYMENT_AUTHORIZATION"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void missingConfigFailsClosed() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void blankPathFailsClosedOrStartupFails() {
        assertThat(provider(true, " ", 1_048_576L).currentSummary()).isEmpty();
    }

    @Test
    void pathTraversalRejected() {
        assertUnavailable(provider(true, Path.of("..", "current-summary.json")));
    }

    @Test
    void requiresConfiguredPathUnderAllowedBaseDirectory() throws Exception {
        Path artifact = writeSummary(validSummary());

        Optional<ShadowPerformanceSummary> result = provider(true, tempDir, artifact).currentSummary();

        assertThat(result).contains(validSummary());
    }

    @Test
    void rejectsPathOutsideAllowedBaseDirectory() throws Exception {
        Path artifact = writeSummary(validSummary());
        Path outsideBaseDir = tempDir.resolve("allowed");
        Files.createDirectories(outsideBaseDir);

        assertUnavailable(provider(true, outsideBaseDir, artifact));
    }

    @Test
    void rejectsSymlinkArtifact() throws Exception {
        Path artifact = writeSummary(validSummary());
        Path symlink = tempDir.resolve("current-summary-link.json");
        try {
            Files.createSymbolicLink(symlink, artifact);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        assertThat(Files.isSymbolicLink(symlink)).isTrue();
        assertThat(Files.isRegularFile(symlink, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertUnavailable(provider(symlink));
    }

    @Test
    void rejectsNonRegularFile() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void directoryPathRejected() {
        assertUnavailable(provider(tempDir));
    }

    @Test
    void unsupportedFileExtensionRejected() throws Exception {
        Path artifact = tempDir.resolve("current-summary.txt");
        Files.writeString(artifact, validSummaryJson());

        assertUnavailable(provider(artifact));
    }

    @Test
    void relativePathBehaviorIsExplicit() throws Exception {
        Path artifact = writeSummary(validSummary());

        assertThat(provider(true, tempDir, artifact.getFileName()).currentSummary()).contains(validSummary());
    }

    @Test
    void classpathSampleNotLoadedByDefault() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void demoProviderOnlyEnabledByExplicitDemoProfileIfItExists() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void staticProviderNotEnabledByDefault() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void sampleSummaryNotEnabledByDefault() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void prodProfileDoesNotUseStaticProvider() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void sourcePathMustBeExplicit() {
        assertThat(provider(true, null).currentSummary()).isEmpty();
    }

    @Test
    void doesNotReturnHardcodedSampleSummaryByDefault() {
        assertThat(provider(false, null).currentSummary()).isEmpty();
    }

    @Test
    void doesNotReturnBundledFixtureWhenSourceMissing() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotFallbackToStaticProviderWhenArtifactUnavailable() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotReturnStaleSummaryOnReadFailure() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, "{");

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotReturnZeroMetricsWhenSummaryMissing() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void doesNotReturnPartialSummaryWhenValidationFails() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotConvertInvalidSummaryToZeroMetrics() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(Path path) {
        return provider(true, path);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, Path path) {
        return provider(enabled, path, 1_048_576L);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, Path path, long maxSizeBytes) {
        return provider(enabled, tempDir, path, maxSizeBytes);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, String path, long maxSizeBytes) {
        return provider(enabled, tempDir.toString(), path, maxSizeBytes);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, Path baseDir, Path path) {
        return provider(enabled, baseDir, path, 1_048_576L);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, Path baseDir, Path path, long maxSizeBytes) {
        return provider(enabled, baseDir == null ? null : baseDir.toString(), path == null ? null : path.toString(), maxSizeBytes);
    }

    private ArtifactBackedShadowPerformanceSummaryProvider provider(boolean enabled, String baseDir, String path, long maxSizeBytes) {
        return new ArtifactBackedShadowPerformanceSummaryProvider(
                new ShadowPerformanceSummaryCurrentProperties(enabled, baseDir, path, maxSizeBytes),
                objectMapper,
                validator
        );
    }

    private Path writeSummary(ShadowPerformanceSummary summary) throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        objectMapper.writeValue(artifact.toFile(), summary);
        return artifact;
    }

    private String validSummaryJson() throws Exception {
        return objectMapper.writeValueAsString(validSummary());
    }

    private void assertUnavailable(ArtifactBackedShadowPerformanceSummaryProvider provider) {
        assertThatThrownBy(provider::currentSummary)
                .isInstanceOf(ShadowPerformanceSummaryProviderUnavailableException.class);
    }

    private ShadowPerformanceSummary validSummary() {
        return ShadowPerformanceSummaryTestFixtures.validSummary();
    }

    private ShadowPerformanceSummary summaryWithMetrics(double precision, double recall, double falsePositiveRate) {
        ShadowPerformanceSummary summary = validSummary();
        return new ShadowPerformanceSummary(
                summary.summaryType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.model(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        precision,
                        recall,
                        falsePositiveRate,
                        summary.metrics().mlCaughtRulesMissedCount(),
                        summary.metrics().rulesCaughtMlMissedCount(),
                        summary.metrics().missingMlCount(),
                        summary.metrics().missingRulesCount(),
                        summary.metrics().missingProjectionCount(),
                        summary.metrics().notEvaluationEligibleCount()
                ),
                summary.disagreementSummary(),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
    }
}
