package com.frauddetection.alert.governance.promotionreviewreadiness;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

public class ArtifactBackedPromotionReviewReadinessReportProvider implements PromotionReviewReadinessReportProvider {

    private final PromotionReviewReadinessReportCurrentProperties properties;
    private final ObjectMapper objectMapper;
    private final PromotionReviewReadinessReportValidator validator;

    public ArtifactBackedPromotionReviewReadinessReportProvider(
            PromotionReviewReadinessReportCurrentProperties properties,
            ObjectMapper objectMapper,
            PromotionReviewReadinessReportValidator validator
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper.rebuild()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .build();
        this.validator = validator;
    }

    @Override
    public Optional<PromotionReviewReadinessReport> currentReport() {
        if (!properties.enabled() || properties.path() == null || properties.path().isBlank()) {
            return Optional.empty();
        }

        Path artifactPath = configuredArtifactPath();
        assertJsonArtifact(artifactPath);
        assertNoSymlinkDirectory(artifactPath);
        assertRegularFile(artifactPath);
        assertBoundedSize(artifactPath);

        PromotionReviewReadinessReport report = readReport(artifactPath);
        try {
            validator.validate(report);
        } catch (PromotionReviewReadinessReportValidationException exception) {
            throw unavailable();
        }
        return Optional.of(report);
    }

    private Path configuredArtifactPath() {
        String configuredBaseDir = properties.baseDir().trim();
        String configuredPath = properties.path().trim();
        if (configuredBaseDir.contains("..") || configuredPath.contains("..")) {
            throw unavailable();
        }
        try {
            Path baseDir = Path.of(configuredBaseDir).toAbsolutePath().normalize();
            Path artifactPath = Path.of(configuredPath);
            Path normalizedArtifactPath = (artifactPath.isAbsolute() ? artifactPath : baseDir.resolve(artifactPath))
                    .toAbsolutePath()
                    .normalize();
            if (!normalizedArtifactPath.startsWith(baseDir)) {
                throw unavailable();
            }
            return normalizedArtifactPath;
        } catch (InvalidPathException exception) {
            throw unavailable();
        }
    }

    private void assertNoSymlinkDirectory(Path artifactPath) {
        Path parent = artifactPath.getParent();
        while (parent != null) {
            if (Files.isSymbolicLink(parent)) {
                throw unavailable();
            }
            parent = parent.getParent();
        }
    }

    private void assertJsonArtifact(Path artifactPath) {
        Path fileName = artifactPath.getFileName();
        if (fileName == null || !fileName.toString().endsWith(".json")) {
            throw unavailable();
        }
    }

    private void assertRegularFile(Path artifactPath) {
        if (!Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
    }

    private void assertBoundedSize(Path artifactPath) {
        try {
            if (Files.size(artifactPath) > properties.maxSizeBytes()) {
                throw unavailable();
            }
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private PromotionReviewReadinessReport readReport(Path artifactPath) {
        try {
            return objectMapper.readValue(artifactPath.toFile(), PromotionReviewReadinessReport.class);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private PromotionReviewReadinessReportProviderUnavailableException unavailable() {
        return new PromotionReviewReadinessReportProviderUnavailableException("Current promotion review readiness report artifact unavailable.");
    }
}
