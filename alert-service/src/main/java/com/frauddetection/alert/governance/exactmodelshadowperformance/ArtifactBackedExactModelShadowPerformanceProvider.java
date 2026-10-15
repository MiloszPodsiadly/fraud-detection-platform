package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.governance.artifact.GovernanceArtifactReadException;
import com.frauddetection.alert.governance.artifact.GovernanceArtifactReader;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

final class ArtifactBackedExactModelShadowPerformanceProvider implements ExactModelShadowPerformanceProvider {

    private static final String SUMMARY_FILENAME = "exact_model_shadow_performance.json";
    private static final String MANIFEST_FILENAME = "manifest.json";
    private static final String ARTIFACT_SET_VERSION = "exact-model-shadow-performance-artifact-set-v1";
    private static final String SCHEMA_VERSION = "exact-model-shadow-performance-v1";
    private static final String REPORT_TYPE = "EXACT_MODEL_SHADOW_PERFORMANCE_V1";
    private static final long MAX_MANIFEST_SIZE_BYTES = 65_536L;
    private static final Set<String> ENTRIES = Set.of(SUMMARY_FILENAME, MANIFEST_FILENAME);
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "artifactSetVersion", "schemaVersion", "reportType", "selectionKey", "generatedAt", "files");
    private static final Set<String> FILE_FIELDS = Set.of("name", "sha256", "sizeBytes");

    private final ExactModelShadowPerformanceProperties properties;
    private final ObjectMapper objectMapper;
    private final ExactModelShadowPerformanceValidator validator;
    private final GovernanceArtifactReader artifactReader;

    ArtifactBackedExactModelShadowPerformanceProvider(
            ExactModelShadowPerformanceProperties properties,
            ObjectMapper objectMapper,
            ExactModelShadowPerformanceValidator validator
    ) {
        this(properties, objectMapper, validator, new GovernanceArtifactReader());
    }

    ArtifactBackedExactModelShadowPerformanceProvider(
            ExactModelShadowPerformanceProperties properties,
            ObjectMapper objectMapper,
            ExactModelShadowPerformanceValidator validator,
            GovernanceArtifactReader artifactReader
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper.rebuild()
                .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false)
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, true)
                .build();
        this.validator = validator;
        this.artifactReader = artifactReader;
    }

    @Override
    public Optional<JsonNode> find(ExactModelShadowPerformanceSelector selector) {
        try {
            String selectionKey = selector.selectionKey();
            Path root = Path.of(properties.baseDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable();
            }
            Path directory = root.resolve(selectionKey);
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path summaryPath = artifactReader.resolveConfiguredFile(
                    properties.baseDir(), selectionKey + "/" + SUMMARY_FILENAME, SUMMARY_FILENAME);
            Path manifestPath = artifactReader.resolveSibling(summaryPath, MANIFEST_FILENAME);
            artifactReader.requireExactDirectoryEntries(directory, ENTRIES);
            byte[] firstManifest = artifactReader.read(manifestPath, MAX_MANIFEST_SIZE_BYTES);
            byte[] firstSummary = artifactReader.read(summaryPath, properties.maxSummarySizeBytes());
            JsonNode manifest = tree(firstManifest);
            JsonNode summary = tree(firstSummary);
            validator.validate(summary, selector);
            validateManifest(manifest, firstSummary, summary, selectionKey);
            require(Arrays.equals(firstSummary, GovernanceCanonicalJson.serialize(objectMapper, summary)),
                    "summary JSON is not canonical");
            require(Arrays.equals(firstManifest, GovernanceCanonicalJson.serialize(objectMapper, manifest)),
                    "manifest JSON is not canonical");
            byte[] secondManifest = artifactReader.read(manifestPath, MAX_MANIFEST_SIZE_BYTES);
            byte[] secondSummary = artifactReader.read(summaryPath, properties.maxSummarySizeBytes());
            artifactReader.requireExactDirectoryEntries(directory, ENTRIES);
            if (!Arrays.equals(firstManifest, secondManifest) || !Arrays.equals(firstSummary, secondSummary)) {
                throw unavailable();
            }
            return Optional.of(summary);
        } catch (GovernanceArtifactReadException | ExactModelShadowPerformanceValidationException
                 | InvalidPathException | SecurityException exception) {
            throw unavailable();
        }
    }

    private void validateManifest(JsonNode manifest, byte[] summaryPayload, JsonNode summary, String selectionKey) {
        exactFields(manifest, MANIFEST_FIELDS, "manifest");
        require(ARTIFACT_SET_VERSION.equals(text(manifest, "artifactSetVersion")), "artifactSetVersion unsupported");
        require(SCHEMA_VERSION.equals(text(manifest, "schemaVersion")), "schemaVersion unsupported");
        require(REPORT_TYPE.equals(text(manifest, "reportType")), "reportType unsupported");
        require(selectionKey.equals(text(manifest, "selectionKey")), "selectionKey mismatch");
        require(text(summary, "generatedAt").equals(text(manifest, "generatedAt")), "generatedAt mismatch");
        JsonNode files = manifest.get("files");
        require(files != null && files.isArray() && files.size() == 1, "manifest file set unsupported");
        JsonNode entry = files.get(0);
        exactFields(entry, FILE_FIELDS, "manifest file");
        require(SUMMARY_FILENAME.equals(text(entry, "name")), "manifest filename unsupported");
        JsonNode size = entry.get("sizeBytes");
        require(size != null && size.isIntegralNumber() && size.canConvertToLong()
                && size.longValue() == summaryPayload.length, "manifest size mismatch");
        require(sha256(summaryPayload).equals(text(entry, "sha256")), "manifest digest mismatch");
    }

    private JsonNode tree(byte[] payload) {
        try {
            JsonNode value = objectMapper.readTree(payload);
            require(value != null && value.isObject(), "artifact must be an object");
            return value;
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        require(node != null && node.isTextual(), field + " must be text");
        return node.textValue();
    }

    private void exactFields(JsonNode value, Set<String> expected, String label) {
        require(value != null && value.isObject() && value.size() == expected.size(), label + " field set unsupported");
        expected.forEach(field -> require(value.has(field), label + " missing " + field));
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
            throw new ExactModelShadowPerformanceValidationException(message);
        }
    }

    private ExactModelShadowPerformanceUnavailableException unavailable() {
        return new ExactModelShadowPerformanceUnavailableException("Exact model Shadow Performance unavailable.");
    }
}
