package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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
        this.objectMapper = objectMapper.rebuild()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .build();
        this.validator = validator;
    }

    @Override
    public Optional<ShadowPerformanceSummary> currentSummary() {
        if (!properties.enabled() || properties.path() == null || properties.path().isBlank()) {
            return Optional.empty();
        }

        Path artifactPath = configuredArtifactPath();
        assertJsonArtifact(artifactPath);
        assertNoSymlinkDirectory(artifactPath);
        assertRegularFile(artifactPath);

        ShadowPerformanceSummary summary = readSummary(readBoundedArtifact(artifactPath));
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

    private byte[] readBoundedArtifact(Path artifactPath) {
        long maxSizeBytes = properties.maxSizeBytes();
        if (maxSizeBytes < 0 || maxSizeBytes >= Integer.MAX_VALUE) {
            throw unavailable();
        }
        try {
            try (InputStream stream = Files.newInputStream(artifactPath)) {
                byte[] payload = stream.readNBytes((int) maxSizeBytes + 1);
                if (payload.length > maxSizeBytes) {
                    throw unavailable();
                }
                return payload;
            }
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private ShadowPerformanceSummary readSummary(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ShadowPerformanceSummary.class);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private ShadowPerformanceSummaryProviderUnavailableException unavailable() {
        return new ShadowPerformanceSummaryProviderUnavailableException("Current shadow performance summary artifact unavailable.");
    }
}
