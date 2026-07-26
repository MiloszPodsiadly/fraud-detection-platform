package com.frauddetection.alert.governance.shadowperformance;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class ArtifactBackedShadowPerformanceSummaryProvider implements ShadowPerformanceSummaryProvider {

    private static final String SUMMARY_FILENAME = "current-summary.json";
    private static final String MANIFEST_FILENAME = "manifest.json";
    private static final String ARTIFACT_SET_REPORT_TYPE = "SHADOW_PERFORMANCE_ARTIFACT_SET_V1";
    private static final String ARTIFACT_SET_VERSION = "shadow-performance-artifact-set-v1";
    private static final long MAX_MANIFEST_SIZE_BYTES = 65_536L;
    private static final Set<String> MANIFEST_FIELDS = Set.of("reportType", "artifactSetVersion", "generatedAt", "files");
    private static final Set<String> FILE_ENTRY_FIELDS = Set.of("path", "sha256", "sizeBytes");

    private final ShadowPerformanceSummaryCurrentProperties properties;
    private final ObjectMapper objectMapper;
    private final ShadowPerformanceSummaryValidator validator;
    private final ArtifactReader artifactReader;

    public ArtifactBackedShadowPerformanceSummaryProvider(
            ShadowPerformanceSummaryCurrentProperties properties,
            ObjectMapper objectMapper,
            ShadowPerformanceSummaryValidator validator
    ) {
        this(properties, objectMapper, validator, new PortableArtifactReader());
    }

    ArtifactBackedShadowPerformanceSummaryProvider(
            ShadowPerformanceSummaryCurrentProperties properties,
            ObjectMapper objectMapper,
            ShadowPerformanceSummaryValidator validator,
            ArtifactReader artifactReader
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper.rebuild()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .build();
        this.validator = validator;
        this.artifactReader = artifactReader;
    }

    @Override
    public Optional<ShadowPerformanceSummary> currentSummary() {
        if (!properties.enabled() || properties.path() == null || properties.path().isBlank()) {
            return Optional.empty();
        }

        Path artifactPath = configuredArtifactPath();
        assertJsonArtifact(artifactPath);
        requireFileName(artifactPath, SUMMARY_FILENAME);
        assertNoSymlinkDirectory(artifactPath);
        assertRegularFile(artifactPath);
        Path manifestPath = artifactPath.resolveSibling(MANIFEST_FILENAME);
        assertJsonArtifact(manifestPath);
        requireFileName(manifestPath, MANIFEST_FILENAME);
        assertNoSymlinkDirectory(manifestPath);
        assertRegularFile(manifestPath);

        byte[] manifestPayload = readBoundedArtifact(manifestPath, MAX_MANIFEST_SIZE_BYTES);
        byte[] summaryPayload = readBoundedArtifact(artifactPath, properties.maxSizeBytes());
        ShadowPerformanceSummary summary = readSummary(summaryPayload);
        try {
            validator.validate(summary);
            validateManifest(manifestPayload, summaryPayload, summary);
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

    private void requireFileName(Path artifactPath, String expected) {
        Path fileName = artifactPath.getFileName();
        if (fileName == null || !expected.equals(fileName.toString())) {
            throw unavailable();
        }
    }

    private void assertRegularFile(Path artifactPath) {
        if (!Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable();
        }
    }

    private byte[] readBoundedArtifact(Path artifactPath, long maxSizeBytes) {
        if (maxSizeBytes < 0 || maxSizeBytes >= Integer.MAX_VALUE) {
            throw unavailable();
        }
        try {
            return artifactReader.read(artifactPath, maxSizeBytes);
        } catch (IOException | SecurityException exception) {
            throw unavailable();
        }
    }

    private void validateManifest(byte[] manifestPayload, byte[] summaryPayload, ShadowPerformanceSummary summary) {
        JsonNode manifest = readJsonTree(manifestPayload);
        require(manifest.isObject(), "shadow manifest must be an object");
        require(manifest.size() == MANIFEST_FIELDS.size(), "shadow manifest field set is unsupported");
        for (String field : MANIFEST_FIELDS) {
            require(manifest.has(field), "shadow manifest missing field");
        }
        require(ARTIFACT_SET_REPORT_TYPE.equals(text(manifest.get("reportType"))), "shadow manifest reportType unsupported");
        require(ARTIFACT_SET_VERSION.equals(text(manifest.get("artifactSetVersion"))), "shadow manifest artifactSetVersion unsupported");
        require(summary.generatedAt().equals(text(manifest.get("generatedAt"))), "shadow manifest generatedAt mismatch");
        JsonNode files = manifest.get("files");
        require(files.isArray() && files.size() == 1, "shadow manifest must list one artifact");
        JsonNode entry = files.get(0);
        require(entry.isObject(), "shadow manifest file entry must be an object");
        require(entry.size() == FILE_ENTRY_FIELDS.size(), "shadow manifest file entry field set is unsupported");
        for (String field : FILE_ENTRY_FIELDS) {
            require(entry.has(field), "shadow manifest file entry missing field");
        }
        require(SUMMARY_FILENAME.equals(text(entry.get("path"))), "shadow manifest lists unsupported artifact");
        require(entry.get("sizeBytes").isIntegralNumber(), "shadow manifest sizeBytes must be an integer");
        long sizeBytes = entry.get("sizeBytes").longValue();
        require(sizeBytes >= 0, "shadow manifest sizeBytes must be non-negative");
        require(sizeBytes == summaryPayload.length, "shadow manifest sizeBytes mismatch");
        String sha256 = text(entry.get("sha256"));
        require(sha256 != null && sha256.matches("^[a-f0-9]{64}$"), "shadow manifest sha256 unsupported");
        require(sha256.equals(sha256(summaryPayload)), "shadow manifest sha256 mismatch");
    }

    private ShadowPerformanceSummary readSummary(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ShadowPerformanceSummary.class);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private JsonNode readJsonTree(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw unavailable();
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ShadowPerformanceSummaryValidationException(message);
        }
    }

    private ShadowPerformanceSummaryProviderUnavailableException unavailable() {
        return new ShadowPerformanceSummaryProviderUnavailableException("Current shadow performance summary artifact unavailable.");
    }

    @FunctionalInterface
    interface ArtifactReader {
        byte[] read(Path artifactPath, long maxSizeBytes) throws IOException;
    }

    static final class PortableArtifactReader implements ArtifactReader {

        private static final int BUFFER_SIZE = 8_192;

        private final Consumer<Path> afterPreReadAttributes;

        PortableArtifactReader() {
            this(path -> {
            });
        }

        PortableArtifactReader(Consumer<Path> afterPreReadAttributes) {
            this.afterPreReadAttributes = afterPreReadAttributes;
        }

        @Override
        public byte[] read(Path artifactPath, long maxSizeBytes) throws IOException {
            /*
             * Portable mitigation: this is not race-free on filesystems without descriptor-relative secure directory
             * APIs, but it avoids symlink following and rejects observable fileKey/size/mtime changes around the read.
             */
            BasicFileAttributes before = regularAttributes(artifactPath);
            if (before.size() > maxSizeBytes) {
                throw new IOException("artifact exceeds maximum size");
            }
            afterPreReadAttributes.accept(artifactPath);
            byte[] payload;
            try (FileChannel channel = FileChannel.open(artifactPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                payload = readBounded(channel, maxSizeBytes);
            }
            BasicFileAttributes after = regularAttributes(artifactPath);
            if (changed(before, after) || payload.length != after.size()) {
                throw new IOException("artifact changed while being read");
            }
            return payload;
        }

        private BasicFileAttributes regularAttributes(Path artifactPath) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    artifactPath,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile()) {
                throw new IOException("artifact must be a regular file");
            }
            return attributes;
        }

        private byte[] readBounded(FileChannel channel, long maxSizeBytes) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream((int) Math.min(channel.size(), BUFFER_SIZE));
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            long total = 0;
            while (channel.read(buffer) != -1) {
                buffer.flip();
                total += buffer.remaining();
                if (total > maxSizeBytes) {
                    throw new IOException("artifact exceeds maximum size");
                }
                payload.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                buffer.clear();
            }
            return payload.toByteArray();
        }

        private boolean changed(BasicFileAttributes before, BasicFileAttributes after) {
            return !Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime());
        }
    }
}
