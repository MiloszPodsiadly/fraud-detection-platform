package com.frauddetection.alert.governance.modelcard;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ArtifactBackedModelCardProvider implements ModelCardProvider {

    private static final String CARD_FILENAME = "ml_model_card.json";
    private static final String MARKDOWN_FILENAME = "ml_model_card.md";
    private static final String MANIFEST_FILENAME = "manifest.json";
    private static final String ARTIFACT_SET_VERSION = "ml-model-card-artifact-set-v1";
    private static final String REPORT_TYPE = "ML_MODEL_CARD_V1";
    private static final long MAX_MANIFEST_SIZE_BYTES = 65_536L;
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "artifactSetVersion", "files", "generatedAt", "reportType"
    );
    private static final Set<String> FILE_FIELDS = Set.of("name", "sha256", "sizeBytes");
    private static final Set<String> ARTIFACT_FILENAMES = Set.of(
            CARD_FILENAME, MARKDOWN_FILENAME, MANIFEST_FILENAME
    );

    private final ModelCardCurrentProperties properties;
    private final ObjectMapper objectMapper;
    private final ModelCardValidator validator;
    private final GovernanceArtifactReader artifactReader;

    public ArtifactBackedModelCardProvider(
            ModelCardCurrentProperties properties,
            ObjectMapper objectMapper,
            ModelCardValidator validator
    ) {
        this(properties, objectMapper, validator, new GovernanceArtifactReader());
    }

    ArtifactBackedModelCardProvider(
            ModelCardCurrentProperties properties,
            ObjectMapper objectMapper,
            ModelCardValidator validator,
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
    public Optional<ModelCard> currentCard() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            Path cardPath = artifactReader.resolveConfiguredFile(
                    properties.baseDir(),
                    properties.path(),
                    CARD_FILENAME
            );
            Path markdownPath = artifactReader.resolveSibling(cardPath, MARKDOWN_FILENAME);
            Path manifestPath = artifactReader.resolveSibling(cardPath, MANIFEST_FILENAME);
            artifactReader.requireExactDirectoryEntries(cardPath.getParent(), ARTIFACT_FILENAMES);
            byte[] firstManifest = artifactReader.read(manifestPath, MAX_MANIFEST_SIZE_BYTES);
            byte[] cardPayload = artifactReader.read(cardPath, properties.maxCardSizeBytes());
            byte[] markdownPayload = artifactReader.read(markdownPath, properties.maxMarkdownSizeBytes());
            JsonNode cardTree = readTree(cardPayload);
            ModelCard card = readCard(cardPayload);
            validator.validate(card);
            validateCanonicalArtifacts(firstManifest, cardPayload, markdownPayload, cardTree);
            validateManifest(firstManifest, cardPayload, markdownPayload, card);
            byte[] secondManifest = artifactReader.read(manifestPath, MAX_MANIFEST_SIZE_BYTES);
            byte[] secondCard = artifactReader.read(cardPath, properties.maxCardSizeBytes());
            byte[] secondMarkdown = artifactReader.read(markdownPath, properties.maxMarkdownSizeBytes());
            artifactReader.requireExactDirectoryEntries(cardPath.getParent(), ARTIFACT_FILENAMES);
            if (!Arrays.equals(firstManifest, secondManifest)
                    || !Arrays.equals(cardPayload, secondCard)
                    || !Arrays.equals(markdownPayload, secondMarkdown)) {
                throw unavailable();
            }
            return Optional.of(card);
        } catch (GovernanceArtifactReadException | ModelCardValidationException exception) {
            throw unavailable();
        }
    }

    private ModelCard readCard(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ModelCard.class);
        } catch (JacksonException exception) {
            throw unavailable();
        }
    }

    private void validateManifest(
            byte[] manifestPayload,
            byte[] cardPayload,
            byte[] markdownPayload,
            ModelCard card
    ) {
        JsonNode manifest = readTree(manifestPayload);
        exactFields(manifest, MANIFEST_FIELDS, "manifest");
        require(ARTIFACT_SET_VERSION.equals(text(manifest, "artifactSetVersion")),
                "artifactSetVersion unsupported");
        require(REPORT_TYPE.equals(text(manifest, "reportType")), "reportType unsupported");
        require(card.generatedAt().equals(text(manifest, "generatedAt")), "generatedAt mismatch");
        JsonNode files = manifest.get("files");
        require(files != null && files.isArray() && files.size() == 2, "manifest file set unsupported");
        require(CARD_FILENAME.equals(text(files.get(0), "name"))
                        && MARKDOWN_FILENAME.equals(text(files.get(1), "name")),
                "manifest file order unsupported");
        Map<String, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : files) {
            exactFields(entry, FILE_FIELDS, "manifest file");
            String name = text(entry, "name");
            require(Set.of(CARD_FILENAME, MARKDOWN_FILENAME).contains(name), "manifest filename unsupported");
            require(entries.put(name, entry) == null, "manifest filename duplicated");
        }
        require(entries.size() == 2, "manifest file set unsupported");
        validateEntry(entries.get(CARD_FILENAME), cardPayload);
        validateEntry(entries.get(MARKDOWN_FILENAME), markdownPayload);
    }

    private void validateCanonicalArtifacts(
            byte[] manifestPayload,
            byte[] cardPayload,
            byte[] markdownPayload,
            JsonNode cardTree
    ) {
        require(Arrays.equals(cardPayload, ModelCardCanonicalArtifact.json(objectMapper, cardTree)),
                "model card JSON is not canonical");
        require(Arrays.equals(markdownPayload, ModelCardCanonicalArtifact.markdown(cardTree)),
                "model card markdown is not canonical");
        JsonNode manifest = readTree(manifestPayload);
        require(Arrays.equals(manifestPayload, ModelCardCanonicalArtifact.json(objectMapper, manifest)),
                "model card manifest is not canonical");
    }

    private void validateEntry(JsonNode entry, byte[] payload) {
        JsonNode size = entry.get("sizeBytes");
        require(size != null && size.isIntegralNumber() && size.canConvertToLong()
                && size.longValue() == payload.length, "manifest size mismatch");
        String expectedSha = text(entry, "sha256");
        require(expectedSha.matches("^[0-9a-f]{64}$") && expectedSha.equals(sha256(payload)),
                "manifest digest mismatch");
    }

    private JsonNode readTree(byte[] payload) {
        try {
            return objectMapper.readTree(payload);
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
        for (String field : expected) {
            require(value.has(field), label + " missing field");
        }
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
            throw new ModelCardValidationException(message);
        }
    }

    private ModelCardProviderUnavailableException unavailable() {
        return new ModelCardProviderUnavailableException("Current Model Card artifact unavailable.");
    }
}
