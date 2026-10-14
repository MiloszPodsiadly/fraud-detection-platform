package com.frauddetection.alert.governance.modelcard;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactBackedModelCardProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelCardValidator validator = new ModelCardValidator();

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
    void disabledOrUnconfiguredProviderReturnsEmpty() {
        assertThat(provider(false, null).currentCard()).isEmpty();
        assertThat(provider(true, " ").currentCard()).isEmpty();
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
    void rejectsTraversalAndNonCanonicalFilename() {
        assertThatThrownBy(() -> provider(true, "../ml_model_card.json").currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class);
        assertThatThrownBy(() -> provider(tempDir.resolve("latest.json")).currentCard())
                .isInstanceOf(ModelCardProviderUnavailableException.class);
    }

    private Path writeArtifactSet(ModelCard card) throws Exception {
        Path cardPath = tempDir.resolve("ml_model_card.json");
        Path markdownPath = tempDir.resolve("ml_model_card.md");
        byte[] cardPayload = objectMapper.writeValueAsBytes(card);
        byte[] markdownPayload = "# ML Model Card\n".getBytes(StandardCharsets.UTF_8);
        Files.write(cardPath, cardPayload);
        Files.write(markdownPath, markdownPayload);
        String manifest = """
                {"artifactSetVersion":"ml-model-card-artifact-set-v1","files":[
                {"name":"ml_model_card.json","sha256":"%s","sizeBytes":%d},
                {"name":"ml_model_card.md","sha256":"%s","sizeBytes":%d}],
                "generatedAt":"%s","reportType":"ML_MODEL_CARD_V1"}
                """.formatted(
                sha256(cardPayload), cardPayload.length, sha256(markdownPayload), markdownPayload.length,
                ModelCardTestFixtures.GENERATED_AT
        );
        Files.writeString(tempDir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return cardPath;
    }

    private ArtifactBackedModelCardProvider provider(Path path) {
        return provider(true, path.toString());
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
