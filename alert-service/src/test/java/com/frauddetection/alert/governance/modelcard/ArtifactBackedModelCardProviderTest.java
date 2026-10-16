package com.frauddetection.alert.governance.modelcard;

import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;

import com.frauddetection.alert.governance.artifact.GovernanceArtifactReader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactBackedModelCardProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardValidator validator =
            new ModelCardValidator(new ModelEvaluationGovernanceValidator());

    @TempDir
    Path tempDir;

    @Test
    void readsOnlyConfiguredManifestBackedArtifactSet() throws Exception {
        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));

        ModelCard result = provider(cardPath).currentCard().orElseThrow();

        assertThat(result.subject().modelArtifactSha256()).isEqualTo(ModelCardTestFixtures.ARTIFACT_SHA);
        assertThat(result.promotionState()).isEqualTo("NOT_EVALUATED_FOR_PROMOTION");
    }

    @Test
    void readsCanonicalPythonGeneratedAvailableGoldenArtifact() throws Exception {
        Path cardPath = Path.of(Objects.requireNonNull(getClass().getResource(
                "/fixtures/model-card/python-generated-available-v1/ml_model_card.json"
        )).toURI());

        ModelCard result = provider(cardPath.getParent(), cardPath).currentCard().orElseThrow();

        assertThat(result.evaluationEvidence().get("status").textValue()).isEqualTo("AVAILABLE");
        assertThat(result.evaluationSummary().at("/population/recordsEvaluated").intValue()).isPositive();
        assertThat(result.subject().modelArtifactSha256())
                .isEqualTo("55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6");
    }

    @Test
    void disabledProviderReturnsEmpty() {
        assertThat(provider(false, null).currentCard()).isEmpty();
    }

    @Test
    void configuredMissingOrCorruptArtifactFailsClosed() throws Exception {
        assertThatThrownBy(() -> provider(tempDir.resolve("ml_model_card.json")).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class);

        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        Files.writeString(cardPath.resolveSibling("ml_model_card.md"), "changed", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider(cardPath).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class)
                .hasMessage("Current Model Card artifact unavailable.");
    }

    @Test
    void rejectsSelfConsistentButNonCanonicalArtifacts() throws Exception {
        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));

        byte[] canonicalCard = Files.readAllBytes(cardPath);
        Files.writeString(cardPath, "  " + new String(canonicalCard, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        rewriteManifest(cardPath);
        assertUnavailable(cardPath);

        cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        Files.writeString(cardPath.resolveSibling("ml_model_card.md"), "# recomputed but not derived\n",
                StandardCharsets.UTF_8);
        rewriteManifest(cardPath);
        assertUnavailable(cardPath);
    }

    @Test
    void rejectsNonCanonicalArtifactSetShapeAndManifest() throws Exception {
        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        Files.writeString(tempDir.resolve("unexpected.txt"), "unexpected", StandardCharsets.UTF_8);
        assertUnavailable(cardPath);

        Files.delete(tempDir.resolve("unexpected.txt"));
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readAllBytes(tempDir.resolve("manifest.json")));
        ArrayNode files = (ArrayNode) manifest.get("files");
        JsonNode first = files.remove(0);
        files.add(first);
        Files.write(tempDir.resolve("manifest.json"), ModelCardCanonicalArtifact.json(objectMapper, manifest));
        assertUnavailable(cardPath);
    }

    @Test
    void rejectsDuplicateCardKeysAndUnsupportedManifestFields() throws Exception {
        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        String card = Files.readString(cardPath, StandardCharsets.UTF_8);
        Files.writeString(
                cardPath,
                card.replaceFirst("\\{", "{\"cardType\":\"ML_MODEL_CARD_V1\","),
                StandardCharsets.UTF_8
        );
        rewriteManifest(cardPath);
        assertUnavailable(cardPath);

        cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readAllBytes(tempDir.resolve("manifest.json")));
        manifest.put("unsupported", true);
        Files.write(tempDir.resolve("manifest.json"), ModelCardCanonicalArtifact.json(objectMapper, manifest));
        assertUnavailable(cardPath);
    }

    @Test
    void rejectsArtifactSetChangedBetweenFirstAndSecondRead() throws Exception {
        Path cardPath = writeArtifactSet(ModelCardTestFixtures.validUnavailableCard(objectMapper));
        AtomicInteger readCount = new AtomicInteger();
        GovernanceArtifactReader reader = new GovernanceArtifactReader(path -> {
            if (readCount.incrementAndGet() == 3) {
                try {
                    Files.writeString(cardPath, "changed between reads\n", StandardCharsets.UTF_8);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        });

        assertThatThrownBy(() -> provider(cardPath.getParent(), cardPath, reader).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class)
                .hasMessage("Current Model Card artifact unavailable.");
    }

    @Test
    void rejectsTraversalAndNonCanonicalFilename() {
        assertThatThrownBy(() -> provider(true, "../ml_model_card.json").currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class);
        assertThatThrownBy(() -> provider(tempDir.resolve("latest.json")).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class);
    }

    @Test
    void matchingManifestCannotAuthorizeSemanticallyInvalidMetrics() throws Exception {
        ModelCard valid = ModelCardTestFixtures.validAvailableCard(objectMapper);
        ObjectNode summary = (ObjectNode) valid.evaluationSummary().deepCopy();
        ((ObjectNode) summary.at("/supportedMetrics/mlPredictionMetrics/precision")).put("value", 0.95);
        ModelCard invalid = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), summary, valid.verifiedUsageBoundary()
        );
        Path cardPath = writeArtifactSet(invalid);

        assertThatThrownBy(() -> provider(cardPath).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class)
                .hasMessage("Current Model Card artifact unavailable.");
    }

    @Test
    void matchingManifestWithNullUsageAuthorityFailsClosed() throws Exception {
        ModelCard valid = ModelCardTestFixtures.validUnavailableCard(objectMapper);
        ModelCard invalid = new ModelCard(
                valid.schemaVersion(), valid.cardType(), valid.generatedAt(), valid.subject(), valid.promotionState(),
                valid.modelArtifactVerification(), valid.evaluationEvidence(), valid.evaluationSummary(),
                new ModelCard.VerifiedUsageBoundary(
                        valid.verifiedUsageBoundary().allowedModes(),
                        valid.verifiedUsageBoundary().intendedUse(),
                        valid.verifiedUsageBoundary().notIntendedUse(),
                        valid.verifiedUsageBoundary().useLimitations(),
                        "NONE", null, "NONE", "NONE", "NONE"
                )
        );
        Path cardPath = writeArtifactSet(invalid);

        assertThatThrownBy(() -> provider(cardPath).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class)
                .hasMessage("Current Model Card artifact unavailable.");
    }

    private Path writeArtifactSet(ModelCard card) throws Exception {
        Path cardPath = tempDir.resolve("ml_model_card.json");
        Path markdownPath = tempDir.resolve("ml_model_card.md");
        JsonNode cardTree = objectMapper.valueToTree(card);
        byte[] cardPayload = ModelCardCanonicalArtifact.json(objectMapper, cardTree);
        byte[] markdownPayload = ModelCardCanonicalArtifact.markdown(cardTree);
        Files.write(cardPath, cardPayload);
        Files.write(markdownPath, markdownPayload);
        writeManifest(cardPayload, markdownPayload);
        return cardPath;
    }

    private void rewriteManifest(Path cardPath) throws Exception {
        writeManifest(
                Files.readAllBytes(cardPath),
                Files.readAllBytes(cardPath.resolveSibling("ml_model_card.md"))
        );
    }

    private void writeManifest(byte[] cardPayload, byte[] markdownPayload) throws Exception {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("artifactSetVersion", "ml-model-card-artifact-set-v1");
        ArrayNode files = manifest.putArray("files");
        files.addObject()
                .put("name", "ml_model_card.json")
                .put("sha256", sha256(cardPayload))
                .put("sizeBytes", cardPayload.length);
        files.addObject()
                .put("name", "ml_model_card.md")
                .put("sha256", sha256(markdownPayload))
                .put("sizeBytes", markdownPayload.length);
        manifest.put("generatedAt", ModelCardTestFixtures.GENERATED_AT);
        manifest.put("reportType", "ML_MODEL_CARD_V1");
        Files.write(tempDir.resolve("manifest.json"), ModelCardCanonicalArtifact.json(objectMapper, manifest));
    }

    private void assertUnavailable(Path cardPath) {
        assertThatThrownBy(() -> provider(cardPath).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class)
                .hasMessage("Current Model Card artifact unavailable.");
    }

    private ArtifactBackedModelCardProvider provider(Path path) {
        return provider(true, path.toString());
    }

    private ArtifactBackedModelCardProvider provider(Path baseDir, Path path) {
        return provider(baseDir, path, new GovernanceArtifactReader());
    }

    private ArtifactBackedModelCardProvider provider(
            Path baseDir,
            Path path,
            GovernanceArtifactReader artifactReader
    ) {
        return new ArtifactBackedModelCardProvider(
                new ModelCardCurrentProperties(true, baseDir.toString(), path.toString(), 524_288L, 262_144L),
                objectMapper,
                validator,
                artifactReader
        );
    }

    private ArtifactBackedModelCardProvider provider(boolean enabled, String path) {
        return new ArtifactBackedModelCardProvider(
                new ModelCardCurrentProperties(enabled, tempDir.toString(), path, 524_288L, 262_144L),
                objectMapper,
                validator
        );
    }

    private String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }
}
