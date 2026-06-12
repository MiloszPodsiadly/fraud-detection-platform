package com.frauddetection.alert.governance.shadowperformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
        if (!Files.exists(artifactPath)) {
            return Optional.empty();
        }
        if (Files.isDirectory(artifactPath)) {
            throw unavailable();
        }
        assertJsonArtifact(artifactPath);
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
        String configuredPath = properties.path().trim();
        if (configuredPath.contains("..")) {
            throw unavailable();
        }
        try {
            return Path.of(configuredPath).normalize();
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
