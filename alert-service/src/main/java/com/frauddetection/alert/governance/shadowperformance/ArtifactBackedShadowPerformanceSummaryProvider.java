package com.frauddetection.alert.governance.shadowperformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

public class ArtifactBackedShadowPerformanceSummaryProvider implements ShadowPerformanceSummaryProvider {

    private final ShadowPerformanceSummaryCurrentProperties properties;
    private final ObjectMapper objectMapper;
    private final ShadowPerformanceSummaryValidator validator;

    public ArtifactBackedShadowPerformanceSummaryProvider(
            ShadowPerformanceSummaryCurrentProperties properties,
            ObjectMapper objectMapper,
            ShadowPerformanceSummaryValidator validator
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.validator = validator;
    }

    @Override
    public Optional<ShadowPerformanceSummary> currentSummary() {
        if (!properties.enabled() || properties.path() == null || properties.path().isBlank()) {
            return Optional.empty();
        }

        Path artifactPath = configuredArtifactPath();
        assertJsonArtifact(artifactPath);
        assertRegularFile(artifactPath);
        assertBoundedSize(artifactPath);

        ShadowPerformanceSummary summary = readSummary(artifactPath);
        try {
            validator.validate(summary);
        } catch (ShadowPerformanceSummaryValidationException exception) {
            throw unavailable();
        }
        return Optional.of(summary);
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

    private ShadowPerformanceSummary readSummary(Path artifactPath) {
        try {
            return objectMapper.readValue(artifactPath.toFile(), ShadowPerformanceSummary.class);
        } catch (JsonProcessingException exception) {
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private ShadowPerformanceSummaryProviderUnavailableException unavailable() {
        return new ShadowPerformanceSummaryProviderUnavailableException("Current shadow performance summary artifact unavailable.");
    }
}
