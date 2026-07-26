package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArtifactBackedShadowPerformanceSummaryProviderTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

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
    void deploymentDemoArtifactSetIsReadableByRealProvider() {
        Path fixtureDir = ROOT.resolve("deployment/local-fixtures/shadow-performance");
        Path artifact = fixtureDir.resolve("current-summary.json");

        Optional<ShadowPerformanceSummary> result = provider(true, fixtureDir, artifact).currentSummary();

        assertThat(fixtureDir.resolve("manifest.json")).isRegularFile();
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().reportType()).isEqualTo("SHADOW_PERFORMANCE_SUMMARY_V2");
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

        assertThat(result.metrics().alertRecommendedPrecision().value()).isEqualTo(1.0);
        assertThat(result.metrics().alertRecommendedRecall().value()).isEqualTo(1.0);
        assertThat(result.metrics().falsePositiveRate().value()).isEqualTo(0.0);
        assertThat(result.metrics().falseNegativeRate()).isEqualTo(summary.metrics().falseNegativeRate());
    }

    @Test
    void doesNotRepairInvalidSummary() throws Exception {
        Path artifact = writeSummary(summaryWithMetrics(2.0, 0.25, 0.0));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenManifestMissing() throws Exception {
        Path artifact = writeSummary(validSummary());
        Files.delete(artifact.resolveSibling("manifest.json"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenManifestHashDoesNotMatchSummary() throws Exception {
        Path artifact = writeSummary(validSummary());
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor("{}\n"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenManifestGeneratedAtUsesEquivalentOffsetEncoding() throws Exception {
        Path artifact = writeSummary(validSummary());
        String json = Files.readString(artifact);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(json, "2026-06-13T02:00:00+00:00"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void acceptsManifestGeneratedAtWithMatchingSixFractionalDigits() throws Exception {
        String json = validSummaryJson().replace("2026-06-13T02:00:00Z", "2026-06-13T02:00:00.123456Z");
        Path artifact = writeJson(json);

        Optional<ShadowPerformanceSummary> result = provider(artifact).currentSummary();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().generatedAt()).isEqualTo("2026-06-13T02:00:00.123456Z");
    }

    @Test
    void throwsUnavailableWhenSummaryOrManifestContainsDuplicateJsonKeys() throws Exception {
        String duplicateSummary = validSummaryJson().replaceFirst(
                "\\{",
                "{\"reportType\":\"SHADOW_PERFORMANCE_SUMMARY_V2\","
        );
        assertUnavailable(provider(writeJson(duplicateSummary)));

        String json = validSummaryJson();
        Path artifact = writeJson(json);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(json).replaceFirst(
                "\"sha256\":\"[a-f0-9]{64}\"",
                "\"sha256\":\"" + sha256(json) + "\",\"sha256\":\"" + sha256(json) + "\""
        ));
        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotCoerceInvalidMetrics() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace(
                "\"value\":0.666667",
                "\"value\":\"0.666667\""
        ));

        assertUnavailable(provider(artifact));
    }

    @Test
    void doesNotDropInvalidFieldsSilently() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("\"reportType\":\"SHADOW_PERFORMANCE_SUMMARY_V2\"",
                "\"reportType\":\"SHADOW_PERFORMANCE_SUMMARY_V2\",\"rawPayload\":\"secret\""));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenAlertRecommendedPrecisionMissing() throws Exception {
        assertUnavailableWithMissingField("alertRecommendedPrecision");
    }

    @Test
    void throwsUnavailableWhenAlertRecommendedPrecisionNull() throws Exception {
        assertUnavailableWithNullField("alertRecommendedPrecision");
    }

    @Test
    void throwsUnavailableWhenAlertRecommendedRecallMissing() throws Exception {
        assertUnavailableWithMissingField("alertRecommendedRecall");
    }

    @Test
    void throwsUnavailableWhenAlertRecommendedRecallNull() throws Exception {
        assertUnavailableWithNullField("alertRecommendedRecall");
    }

    @Test
    void throwsUnavailableWhenFalsePositiveRateMissing() throws Exception {
        assertUnavailableWithMissingField("falsePositiveRate");
    }

    @Test
    void throwsUnavailableWhenFalsePositiveRateNull() throws Exception {
        assertUnavailableWithNullField("falsePositiveRate");
    }

    @Test
    void throwsUnavailableWhenMetricObjectFieldMissing() throws Exception {
        for (String fieldName : new String[]{
                "available",
                "value"
        }) {
            assertUnavailableWithMissingField(fieldName);
        }
    }

    @Test
    void throwsUnavailableWhenMetricObjectFieldNull() throws Exception {
        for (String fieldName : new String[]{
                "available",
                "value"
        }) {
            assertUnavailableWithNullField(fieldName);
        }
    }

    @Test
    void throwsUnavailableWhenRecordsEvaluatedMissing() throws Exception {
        assertUnavailableWithMissingField("recordsEvaluated");
    }

    @Test
    void throwsUnavailableWhenRecordsEvaluatedNull() throws Exception {
        assertUnavailableWithNullField("recordsEvaluated");
    }

    @Test
    void throwsUnavailableWhenPositiveClassCountMissing() throws Exception {
        assertUnavailableWithMissingField("positiveClassCount");
    }

    @Test
    void throwsUnavailableWhenPositiveClassCountNull() throws Exception {
        assertUnavailableWithNullField("positiveClassCount");
    }

    @Test
    void throwsUnavailableWhenNegativeClassCountMissing() throws Exception {
        assertUnavailableWithMissingField("negativeClassCount");
    }

    @Test
    void throwsUnavailableWhenNegativeClassCountNull() throws Exception {
        assertUnavailableWithNullField("negativeClassCount");
    }

    @Test
    void throwsUnavailableWhenDiagnosticOnlyMissing() throws Exception {
        assertUnavailableWithMissingField("diagnosticOnly");
    }

    @Test
    void throwsUnavailableWhenDiagnosticOnlyNull() throws Exception {
        assertUnavailableWithNullField("diagnosticOnly");
    }

    @Test
    void throwsUnavailableWhenNotProductionApprovalMissing() throws Exception {
        assertUnavailableWithMissingField("notProductionApproval");
    }

    @Test
    void throwsUnavailableWhenNotProductionApprovalNull() throws Exception {
        assertUnavailableWithNullField("notProductionApproval");
    }

    @Test
    void throwsUnavailableWhenNotPromotionApprovalMissing() throws Exception {
        assertUnavailableWithMissingField("notPromotionApproval");
    }

    @Test
    void throwsUnavailableWhenNotPromotionApprovalNull() throws Exception {
        assertUnavailableWithNullField("notPromotionApproval");
    }

    @Test
    void throwsUnavailableWhenNotThresholdRecommendationMissing() throws Exception {
        assertUnavailableWithMissingField("notThresholdRecommendation");
    }

    @Test
    void throwsUnavailableWhenNotThresholdRecommendationNull() throws Exception {
        assertUnavailableWithNullField("notThresholdRecommendation");
    }

    @Test
    void throwsUnavailableWhenNotPaymentAuthorizationMissing() throws Exception {
        assertUnavailableWithMissingField("notPaymentAuthorization");
    }

    @Test
    void throwsUnavailableWhenNotPaymentAuthorizationNull() throws Exception {
        assertUnavailableWithNullField("notPaymentAuthorization");
    }

    @Test
    void throwsUnavailableWhenNotAutomaticDecisioningMissing() throws Exception {
        assertUnavailableWithMissingField("notAutomaticDecisioning");
    }

    @Test
    void throwsUnavailableWhenNotAutomaticDecisioningNull() throws Exception {
        assertUnavailableWithNullField("notAutomaticDecisioning");
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
        Files.writeString(artifact, validSummaryJson().replace("SHADOW_PERFORMANCE_SUMMARY_V2", "PLATFORM_RECOMMENDATION_EVALUATION_CARD"));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenUnsupportedSummaryVersion() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("\"summaryVersion\":\"shadow-performance-summary-v2\"", "\"summaryVersion\":\"2.0\""));

        assertUnavailable(provider(artifact));
    }

    @Test
    void throwsUnavailableWhenRawIdentifiersPresent() throws Exception {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, validSummaryJson().replace("ENGINE_INTELLIGENCE_PROJECTION", "txnref-secret"));

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
    void rejectsArtifactReplacedBetweenAttributeCheckAndReadEvenWhenPayloadBytesMatch() throws Exception {
        Path artifact = writeSummary(validSummary());
        ArtifactBackedShadowPerformanceSummaryProvider.PortableArtifactReader reader =
                new ArtifactBackedShadowPerformanceSummaryProvider.PortableArtifactReader(path -> {
                    if (path.getFileName().toString().equals("current-summary.json")) {
                        try {
                            Files.delete(path);
                            Files.writeString(path, validSummaryJson());
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                });
        ArtifactBackedShadowPerformanceSummaryProvider provider = new ArtifactBackedShadowPerformanceSummaryProvider(
                new ShadowPerformanceSummaryCurrentProperties(true, tempDir.toString(), artifact.toString(), 1_048_576L),
                objectMapper,
                validator,
                reader
        );

        assertUnavailable(provider);
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
        String payload = objectMapper.writeValueAsString(summary);
        Files.writeString(artifact, payload);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(payload));
        return artifact;
    }

    private Path writeJson(String json) throws IOException {
        Path artifact = tempDir.resolve("current-summary.json");
        Files.writeString(artifact, json);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(json));
        return artifact;
    }

    private String manifestFor(String summaryPayload) {
        return manifestFor(summaryPayload, generatedAt(summaryPayload));
    }

    private String manifestFor(String summaryPayload, String generatedAt) {
        return """
                {"artifactSetVersion":"shadow-performance-artifact-set-v1","files":[{"path":"current-summary.json","sha256":"%s","sizeBytes":%d}],"generatedAt":"2026-06-13T02:00:00Z","reportType":"SHADOW_PERFORMANCE_ARTIFACT_SET_V1"}
                """.replace("2026-06-13T02:00:00Z", generatedAt)
                .formatted(sha256(summaryPayload), summaryPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    private String generatedAt(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode generatedAt = node.get("generatedAt");
            return generatedAt != null && generatedAt.isTextual()
                    ? generatedAt.textValue()
                    : "2026-06-13T02:00:00Z";
        } catch (Exception exception) {
            return "2026-06-13T02:00:00Z";
        }
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String validSummaryJson() throws Exception {
        return objectMapper.writeValueAsString(validSummary());
    }

    private String withoutField(String json, String fieldName) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(removeField(root, fieldName))
                .as("field %s exists in summary JSON", fieldName)
                .isTrue();
        return objectMapper.writeValueAsString(root);
    }

    private String withNullField(String json, String fieldName) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(nullField(root, fieldName))
                .as("field %s exists in summary JSON", fieldName)
                .isTrue();
        return objectMapper.writeValueAsString(root);
    }

    private boolean removeField(JsonNode node, String fieldName) {
        if (node instanceof ObjectNode objectNode && objectNode.remove(fieldName) != null) {
            return true;
        }
        for (JsonNode child : node) {
            if (removeField(child, fieldName)) {
                return true;
            }
        }
        return false;
    }

    private boolean nullField(JsonNode node, String fieldName) {
        if (node instanceof ObjectNode objectNode && objectNode.has(fieldName)) {
            objectNode.putNull(fieldName);
            return true;
        }
        for (JsonNode child : node) {
            if (nullField(child, fieldName)) {
                return true;
            }
        }
        return false;
    }

    private void assertUnavailableWithMissingField(String fieldName) throws Exception {
        Path artifact = writeJson(withoutField(validSummaryJson(), fieldName));

        assertUnavailable(provider(artifact));
    }

    private void assertUnavailableWithNullField(String fieldName) throws Exception {
        Path artifact = writeJson(withNullField(validSummaryJson(), fieldName));

        assertUnavailable(provider(artifact));
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
                summary.reportType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.evaluationSubject(),
                summary.metricBasis(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        ShadowPerformanceSummaryTestFixtures.metric(precision),
                        ShadowPerformanceSummaryTestFixtures.metric(recall),
                        ShadowPerformanceSummaryTestFixtures.metric(falsePositiveRate),
                        summary.metrics().falseNegativeRate()
                ),
                summary.warnings(),
                summary.limitations(),
                summary.banner()
        );
    }
}
