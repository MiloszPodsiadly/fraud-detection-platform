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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ArtifactBackedPromotionReviewReadinessReportProviderTest {

    private static final String FIXTURE = "fixtures/promotion-review-readiness/promotion-review-readiness-report.json";
    private static final String REPORT_FILENAME = "promotion-review-readiness-report.json";

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
        assertUnavailable(provider(tempDir.resolve(REPORT_FILENAME)));
    }

    @Test
    void configuredMissingManifestMapsToUnavailable() throws Exception {
        Path artifact = writeReport(PromotionReviewReadinessReportTestFixtures.validReport());
        Files.delete(artifact.resolveSibling("manifest.json"));

        assertUnavailable(provider(artifact));
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
        assertUnavailable(provider(writeJson(withNullField(validReportJson(), "recordsEvaluated"))));
    }

    @Test
    void configuredSchemaInvalidSourceMapsToUnavailable() throws Exception {
        assertUnavailable(provider(writeJson(validReportJson().replace("CURRENT_SUMMARY_PRESENT", "PROMOTION_APPROVED"))));
    }

    @Test
    void configuredIncompleteChecksMapToUnavailable() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root).putArray("checks").add(objectMapper.createObjectNode()
                .put("name", "CURRENT_SUMMARY_PRESENT")
                .put("status", "PASS")
                .put("severity", "INFO"));

        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(root))));
    }

    @Test
    void configuredMissingRequiredCheckMapsToUnavailable() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root).putArray("checks");

        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(root))));
    }

    @Test
    void configuredContradictoryReadinessStatusMapsToUnavailable() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root.get("checks").get(13)).put("status", "INCONCLUSIVE");

        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(root))));
    }

    @Test
    void configuredContradictoryReasonCodesMapToUnavailable() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root).putArray("reasonCodes").add("EXTRA_REASON");

        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(root))));
    }

    @Test
    void configuredCountsAboveFdp123LimitMapToUnavailable() throws Exception {
        JsonNode records = objectMapper.readTree(validReportJson());
        ((ObjectNode) records.get("inputs")).put("recordsEvaluated", 1001);
        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(records))));

        JsonNode minimum = objectMapper.readTree(validReportJson());
        ((ObjectNode) minimum.get("inputs")).put("minimumDiagnosticEvidenceRecords", 1001);
        assertUnavailable(provider(writeJson(objectMapper.writeValueAsString(minimum))));
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
        assertUnavailable(provider(true, Path.of("..", REPORT_FILENAME)));
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
        Path target = tempDir.resolve("target-" + REPORT_FILENAME);
        objectMapper.writeValue(target.toFile(), PromotionReviewReadinessReportTestFixtures.validReport());
        Path symlink = tempDir.resolve(REPORT_FILENAME);
        try {
            Files.createSymbolicLink(symlink, target);
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
        Path artifact = realDirectory.resolve(REPORT_FILENAME);
        objectMapper.writeValue(artifact.toFile(), PromotionReviewReadinessReportTestFixtures.validReport());

        assertUnavailable(provider(true, tempDir, symlinkDirectory.resolve(REPORT_FILENAME)));
    }

    @Test
    void providerDoesNotExposeConfiguredPathWhenUnavailable() {
        Path missing = tempDir.resolve("secret-" + REPORT_FILENAME);

        assertThatThrownBy(provider(missing)::currentReport)
                .isInstanceOf(PromotionReviewReadinessReportProviderUnavailableException.class)
                .hasMessage("Current promotion review readiness report artifact unavailable.")
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining("secret-" + REPORT_FILENAME);
    }

    @Test
    void configuredWrongManifestHashOrSizeMapsToUnavailable() throws Exception {
        Path artifact = writeReport(PromotionReviewReadinessReportTestFixtures.validReport());
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(Files.readString(artifact), "b".repeat(64), null));
        assertUnavailable(provider(artifact));

        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(Files.readString(artifact), null, 1L));
        assertUnavailable(provider(artifact));
    }

    @Test
    void configuredDuplicateJsonKeysMapToUnavailable() throws Exception {
        String duplicateRoot = validReportJson().replaceFirst(
                "\\{",
                "{\"reportType\":\"PROMOTION_REVIEW_READINESS_REPORT_V1\","
        );
        assertUnavailable(provider(writeJson(duplicateRoot)));

        String json = validReportJson();
        Path artifact = writeJson(json);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(json).replaceFirst(
                "\"sha256\":\"[a-f0-9]{64}\"",
                "\"sha256\":\"" + sha256(json) + "\",\"sha256\":\"" + sha256(json) + "\""
        ));
        assertUnavailable(provider(artifact));
    }

    @Test
    void acceptsValidFdp111ReportWithRecordsEvaluatedAbove500() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root.get("inputs")).put("recordsEvaluated", 501);
        ((ObjectNode) root.get("checkInputs")).put("recordsEvaluated", 501);

        Optional<PromotionReviewReadinessReport> result =
                provider(writeJson(objectMapper.writeValueAsString(root))).currentReport();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().inputs().recordsEvaluated()).isEqualTo(501);
    }

    @Test
    void acceptsValidFdp111ReportWithMinimumDiagnosticEvidenceRecordsAbove500() throws Exception {
        JsonNode root = objectMapper.readTree(validReportJson());
        ((ObjectNode) root.get("inputs")).put("minimumDiagnosticEvidenceRecords", 501);
        ((ObjectNode) root.get("inputs")).put("recordsEvaluated", 501);
        ((ObjectNode) root.get("checkInputs")).put("minimumDiagnosticEvidenceRecords", 501);
        ((ObjectNode) root.get("checkInputs")).put("recordsEvaluated", 501);

        Optional<PromotionReviewReadinessReport> result =
                provider(writeJson(objectMapper.writeValueAsString(root))).currentReport();

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().inputs().minimumDiagnosticEvidenceRecords()).isEqualTo(501);
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
        Path artifact = tempDir.resolve(REPORT_FILENAME);
        objectMapper.writeValue(artifact.toFile(), report);
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(Files.readString(artifact)));
        return artifact;
    }

    private Path writeJson(String json) throws IOException {
        return writeJson(REPORT_FILENAME, json);
    }

    private Path writeJson(String fileName, String json) throws IOException {
        Path artifact = tempDir.resolve(fileName);
        Files.writeString(artifact, json);
        if (REPORT_FILENAME.equals(fileName)) {
            Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(json));
        }
        return artifact;
    }

    private Path copyFixture() throws Exception {
        Path artifact = tempDir.resolve(REPORT_FILENAME);
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(stream).isNotNull();
            Files.copy(stream, artifact);
        }
        Files.writeString(artifact.resolveSibling("manifest.json"), manifestFor(Files.readString(artifact)));
        return artifact;
    }

    private String manifestFor(String json) {
        return manifestFor(json, null, null);
    }

    private String manifestFor(String json, String sha256, Long sizeBytes) {
        return """
                {"artifactSetVersion":"promotion-review-readiness-artifact-set-v1","files":[{"path":"%s","sha256":"%s","sizeBytes":%d}],"generatedAt":"%s","reportType":"PROMOTION_REVIEW_READINESS_ARTIFACT_SET_V1"}
                """.formatted(
                REPORT_FILENAME,
                sha256 == null ? sha256(json) : sha256,
                sizeBytes == null ? json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : sizeBytes,
                PromotionReviewReadinessReportTestFixtures.validReport().generatedAt()
        );
    }

    private String sha256(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
