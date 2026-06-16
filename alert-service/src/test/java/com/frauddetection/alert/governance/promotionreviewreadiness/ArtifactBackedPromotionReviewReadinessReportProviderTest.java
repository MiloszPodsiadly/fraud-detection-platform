package com.frauddetection.alert.governance.promotionreviewreadiness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArtifactBackedPromotionReviewReadinessReportProviderTest {

    private static final String FIXTURE = "fixtures/promotion-review-readiness/promotion-review-readiness-report.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromotionReviewReadinessReportValidator validator = spy(new PromotionReviewReadinessReportValidator());

    @TempDir
    Path tempDir;

    @Test
    void returnsCurrentReportFromConfiguredArtifact() throws Exception {
        PromotionReviewReadinessReport report = PromotionReviewReadinessReportTestFixtures.validReport();
        Path artifact = writeReport(report);

        Optional<PromotionReviewReadinessReport> result = provider(artifact).currentReport();

        assertThat(result).contains(report);
        verify(validator).validate(report);
    }

    @Test
    void realFdp111GeneratedReportFixtureCanBeReadByProvider() throws Exception {
        Path artifact = copyFixture();

        Optional<PromotionReviewReadinessReport> result = provider(artifact).currentReport();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().reportType()).isEqualTo(PromotionReviewReadinessReportContract.REPORT_TYPE);
        assertThat(result.orElseThrow().notAnalystRecommendation()).isTrue();
    }

    @Test
    void returnsEmptyWhenProviderDisabledOrPathMissing() {
        assertThat(provider(false, tempDir.resolve("current.json")).currentReport()).isEmpty();
        assertThat(provider(true, null).currentReport()).isEmpty();
        assertThat(provider(true, "   ", 262_144L).currentReport()).isEmpty();
    }

    @Test
    void configuredMissingSourceMapsToUnavailable() {
        assertUnavailable(provider(tempDir.resolve("missing.json")));
    }

    @Test
    void configuredMalformedSourceMapsToUnavailable() throws Exception {
        assertUnavailable(provider(writeJson("{")));
    }

    @Test
    void configuredUnsupportedTypeOrVersionMapsToUnavailable() throws Exception {
        assertUnavailable(provider(writeJson(validReportJson().replace("PROMOTION_REVIEW_READINESS_REPORT_V1", "OTHER"))));
        assertUnavailable(provider(writeJson(validReportJson().replace("\"reportVersion\":\"1.0\"", "\"reportVersion\":\"2.0\""))));
    }

    @Test
    void configuredInvalidReadinessStatusMapsToUnavailable() throws Exception {
        for (String readinessStatus : new String[]{"APPROVED", "PROMOTED", "DIAGNOSTIC_ONLY"}) {
            assertUnavailable(provider(writeJson(validReportJson().replace("\"readinessStatus\":\"REVIEWABLE\"",
                    "\"readinessStatus\":\"" + readinessStatus + "\""))));
        }
    }

    @Test
    void configuredMissingRequiredBooleanMapsToUnavailable() throws Exception {
        for (String field : new String[]{
                "diagnosticOnly",
                "notPromotionApproval",
                "notThresholdRecommendation",
                "notProductionDecisioning",
                "notPaymentAuthorization",
                "notAutomaticDecisioning",
                "notAnalystRecommendation"
        }) {
            assertUnavailable(provider(writeJson(withoutField(validReportJson(), field))));
        }
    }

    @Test
    void configuredNullPrimitiveMapsToUnavailable() throws Exception {
        assertUnavailable(provider(writeJson(withNullField(validReportJson(), "notAnalystRecommendation"))));
        assertUnavailable(provider(writeJson(withNullField(validReportJson(), "recordsAcceptedForEvaluation"))));
    }

    @Test
    void configuredSchemaInvalidSourceMapsToUnavailable() throws Exception {
        assertUnavailable(provider(writeJson(validReportJson().replace("CURRENT_SUMMARY_PRESENT", "PROMOTION_APPROVED"))));
    }

    @Test
    void configuredTooLargeSourceMapsToUnavailable() throws Exception {
        Path artifact = writeJson(validReportJson());

        assertUnavailable(provider(true, artifact, 16));
    }

    @Test
    void configuredDirectoryNonJsonTraversalAndUnreadablePathsMapToUnavailable() throws Exception {
        assertUnavailable(provider(tempDir));
        assertUnavailable(provider(writeJson("report.txt", validReportJson())));
        assertUnavailable(provider(true, Path.of("..", "current-report.json")));
        assertUnavailable(provider(true, "\u0000", 262_144L));
    }

    @Test
    void configuredPathOutsideBaseDirMapsToUnavailable() throws Exception {
        Path artifact = writeReport(PromotionReviewReadinessReportTestFixtures.validReport());
        Path baseDir = tempDir.resolve("allowed");
        Files.createDirectories(baseDir);

        assertUnavailable(provider(true, baseDir, artifact));
    }

    @Test
    void configuredSymlinkFileMapsToUnavailable() throws Exception {
        Path artifact = writeReport(PromotionReviewReadinessReportTestFixtures.validReport());
        Path symlink = tempDir.resolve("current-report-link.json");
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
    void configuredSymlinkDirectoryMapsToUnavailable() throws Exception {
        Path realDirectory = tempDir.resolve("real");
        Files.createDirectories(realDirectory);
        Path symlinkDirectory = tempDir.resolve("linked");
        try {
            Files.createSymbolicLink(symlinkDirectory, realDirectory);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        Path artifact = realDirectory.resolve("current-report.json");
        objectMapper.writeValue(artifact.toFile(), PromotionReviewReadinessReportTestFixtures.validReport());

        assertUnavailable(provider(true, tempDir, symlinkDirectory.resolve("current-report.json")));
    }

    @Test
    void providerDoesNotExposeConfiguredPathWhenUnavailable() {
        Path missing = tempDir.resolve("secret-current-report.json");

        assertThatThrownBy(provider(missing)::currentReport)
                .isInstanceOf(PromotionReviewReadinessReportProviderUnavailableException.class)
                .hasMessage("Current promotion review readiness report artifact unavailable.")
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("secret-current-report.json");
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(Path path) {
        return provider(true, path);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, Path path) {
        return provider(enabled, path, 262_144L);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, Path path, long maxSizeBytes) {
        return provider(enabled, tempDir, path, maxSizeBytes);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, String path, long maxSizeBytes) {
        return provider(enabled, tempDir.toString(), path, maxSizeBytes);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, Path baseDir, Path path) {
        return provider(enabled, baseDir, path, 262_144L);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, Path baseDir, Path path, long maxSizeBytes) {
        return provider(enabled, baseDir == null ? null : baseDir.toString(), path == null ? null : path.toString(), maxSizeBytes);
    }

    private ArtifactBackedPromotionReviewReadinessReportProvider provider(boolean enabled, String baseDir, String path, long maxSizeBytes) {
        return new ArtifactBackedPromotionReviewReadinessReportProvider(
                new PromotionReviewReadinessReportCurrentProperties(enabled, baseDir, path, maxSizeBytes),
                objectMapper,
                validator
        );
    }

    private Path writeReport(PromotionReviewReadinessReport report) throws Exception {
        Path artifact = tempDir.resolve("current-report.json");
        objectMapper.writeValue(artifact.toFile(), report);
        return artifact;
    }

    private Path writeJson(String json) throws IOException {
        return writeJson("current-report.json", json);
    }

    private Path writeJson(String fileName, String json) throws IOException {
        Path artifact = tempDir.resolve(fileName);
        Files.writeString(artifact, json);
        return artifact;
    }

    private Path copyFixture() throws Exception {
        Path artifact = tempDir.resolve("current-report.json");
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(stream).isNotNull();
            Files.copy(stream, artifact);
        }
        return artifact;
    }

    private String validReportJson() throws Exception {
        return objectMapper.writeValueAsString(PromotionReviewReadinessReportTestFixtures.validReport());
    }

    private String withoutField(String json, String fieldName) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(removeField(root, fieldName)).isTrue();
        return objectMapper.writeValueAsString(root);
    }

    private String withNullField(String json, String fieldName) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(nullField(root, fieldName)).isTrue();
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

    private void assertUnavailable(ArtifactBackedPromotionReviewReadinessReportProvider provider) {
        assertThatThrownBy(provider::currentReport)
                .isInstanceOf(PromotionReviewReadinessReportProviderUnavailableException.class);
    }
}
