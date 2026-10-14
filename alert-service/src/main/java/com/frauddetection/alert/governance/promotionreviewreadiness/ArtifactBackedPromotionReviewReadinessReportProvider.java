package com.frauddetection.alert.governance.promotionreviewreadiness;

import com.frauddetection.alert.governance.artifact.GovernanceArtifactReadException;
import com.frauddetection.alert.governance.artifact.GovernanceArtifactReader;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

public class ArtifactBackedPromotionReviewReadinessReportProvider implements PromotionReviewReadinessReportProvider {
    private static final String REPORT_FILENAME = "promotion-review-readiness-report.json";
    private static final String MANIFEST_FILENAME = "manifest.json";
    private static final String ARTIFACT_SET_REPORT_TYPE = "PROMOTION_REVIEW_READINESS_ARTIFACT_SET_V1";
    private static final String ARTIFACT_SET_VERSION = "promotion-review-readiness-artifact-set-v1";
    private static final long MAX_MANIFEST_SIZE_BYTES = 65_536L;
    private static final Set<String> MANIFEST_FIELDS = Set.of("reportType", "artifactSetVersion", "generatedAt", "files");
    private static final Set<String> FILE_ENTRY_FIELDS = Set.of("path", "sha256", "sizeBytes");

    private final PromotionReviewReadinessReportCurrentProperties properties;
    private final ObjectMapper objectMapper;
    private final PromotionReviewReadinessReportValidator validator;
    private final GovernanceArtifactReader artifactReader;

    public ArtifactBackedPromotionReviewReadinessReportProvider(
            PromotionReviewReadinessReportCurrentProperties properties,
            ObjectMapper objectMapper,
            PromotionReviewReadinessReportValidator validator
    ) {
        this(properties, objectMapper, validator, new GovernanceArtifactReader());
    }

    ArtifactBackedPromotionReviewReadinessReportProvider(
            PromotionReviewReadinessReportCurrentProperties properties,
            ObjectMapper objectMapper,
            PromotionReviewReadinessReportValidator validator,
            GovernanceArtifactReader artifactReader
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
    public Optional<PromotionReviewReadinessReport> currentReport() {
        if (!properties.enabled() || properties.path() == null || properties.path().isBlank()) {
            return Optional.empty();
        }

        Path artifactPath = configuredArtifactPath();
        Path manifestPath = sibling(artifactPath, MANIFEST_FILENAME);

        byte[] manifestPayload = readBoundedArtifact(manifestPath, MAX_MANIFEST_SIZE_BYTES);
        byte[] reportPayload = readBoundedArtifact(artifactPath, properties.maxSizeBytes());
        PromotionReviewReadinessReport report = readReport(reportPayload);
        try {
            validator.validate(report);
            validateManifest(manifestPayload, reportPayload, report);
        } catch (PromotionReviewReadinessReportValidationException exception) {
            throw unavailable();
        }
        return Optional.of(report);
    }

    private Path configuredArtifactPath() {
        try {
            return artifactReader.resolveConfiguredFile(properties.baseDir(), properties.path(), REPORT_FILENAME);
        } catch (GovernanceArtifactReadException exception) {
            throw unavailable();
        }
    }

    private Path sibling(Path artifactPath, String expectedFilename) {
        try {
            return artifactReader.resolveSibling(artifactPath, expectedFilename);
        } catch (GovernanceArtifactReadException exception) {
            throw unavailable();
        }
    }

    private byte[] readBoundedArtifact(Path artifactPath, long maxSizeBytes) {
        if (maxSizeBytes < 0 || maxSizeBytes >= Integer.MAX_VALUE) {
            throw unavailable();
        }
        try {
            return artifactReader.read(artifactPath, maxSizeBytes);
        } catch (GovernanceArtifactReadException exception) {
            throw unavailable();
        }
    }

    private PromotionReviewReadinessReport readReport(byte[] payload) {
        try {
            return objectMapper.readValue(payload, PromotionReviewReadinessReport.class);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private void validateManifest(
            byte[] manifestPayload,
            byte[] reportPayload,
            PromotionReviewReadinessReport report
    ) {
        JsonNode manifest = readJsonTree(manifestPayload);
        require(manifest.isObject(), "promotion readiness manifest must be an object");
        require(manifest.size() == MANIFEST_FIELDS.size(), "promotion readiness manifest field set is unsupported");
        for (String field : MANIFEST_FIELDS) {
            require(manifest.has(field), "promotion readiness manifest missing field");
        }
        require(ARTIFACT_SET_REPORT_TYPE.equals(text(manifest.get("reportType"))), "promotion readiness manifest reportType unsupported");
        require(ARTIFACT_SET_VERSION.equals(text(manifest.get("artifactSetVersion"))), "promotion readiness manifest artifactSetVersion unsupported");
        require(report.generatedAt().equals(text(manifest.get("generatedAt"))), "promotion readiness manifest generatedAt mismatch");
        JsonNode files = manifest.get("files");
        require(files.isArray() && files.size() == 1, "promotion readiness manifest must list one artifact");
        JsonNode entry = files.get(0);
        require(entry.isObject(), "promotion readiness manifest file entry must be an object");
        require(entry.size() == FILE_ENTRY_FIELDS.size(), "promotion readiness manifest file entry field set is unsupported");
        for (String field : FILE_ENTRY_FIELDS) {
            require(entry.has(field), "promotion readiness manifest file entry missing field");
        }
        require(REPORT_FILENAME.equals(text(entry.get("path"))), "promotion readiness manifest lists unsupported artifact");
        require(entry.get("sizeBytes").isIntegralNumber(), "promotion readiness manifest sizeBytes must be an integer");
        long sizeBytes = entry.get("sizeBytes").longValue();
        require(sizeBytes >= 0, "promotion readiness manifest sizeBytes must be non-negative");
        require(sizeBytes == reportPayload.length, "promotion readiness manifest sizeBytes mismatch");
        String sha256 = text(entry.get("sha256"));
        require(sha256 != null && sha256.matches("^[a-f0-9]{64}$"), "promotion readiness manifest sha256 unsupported");
        require(sha256.equals(sha256(reportPayload)), "promotion readiness manifest sha256 mismatch");
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
            throw new PromotionReviewReadinessReportValidationException(message);
        }
    }

    private PromotionReviewReadinessReportProviderUnavailableException unavailable() {
        return new PromotionReviewReadinessReportProviderUnavailableException("Current promotion review readiness report artifact unavailable.");
    }
}
