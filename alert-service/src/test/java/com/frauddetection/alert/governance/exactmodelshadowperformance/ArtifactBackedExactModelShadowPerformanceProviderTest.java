package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactBackedExactModelShadowPerformanceProviderTest {

    @TempDir
    Path root;

    @Test
    void readsPythonGeneratedArtifactOnlyByExactSelector() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        copyFixture(root.resolve(selector.selectionKey()));

        JsonNode result = provider().find(selector).orElseThrow();

        assertThat(result.at("/evaluationSummary/evaluationSubject/modelArtifactSha256").textValue())
                .isEqualTo("a".repeat(64));
        assertThat(result.at("/evaluationSummary/supportedMetrics/rulesVsMlDisagreement/disagreementRate/value")
                .doubleValue()).isEqualTo(0.5);
        assertThat(result.at("/operationalDiagnostics/fallbackRate/available").booleanValue()).isFalse();
    }

    @Test
    void missingExactShaDoesNotFallBackToNeighbor() throws Exception {
        ExactModelShadowPerformanceSelector existing = selector("a".repeat(64));
        copyFixture(root.resolve(existing.selectionKey()));

        assertThat(provider().find(selector("b".repeat(64)))).isEmpty();
    }

    @Test
    void corruptSelectedArtifactFailsClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path destination = root.resolve(selector.selectionKey());
        copyFixture(destination);
        Files.writeString(destination.resolve("exact_model_shadow_performance.json"), "{}\n");

        assertThatThrownBy(() -> provider().find(selector))
                .isInstanceOf(ExactModelShadowPerformanceUnavailableException.class);
    }

    @Test
    void configuredMissingArtifactRootIsUnavailableRatherThanNotFound() {
        Path missingRoot = root.resolve("missing-root");
        ArtifactBackedExactModelShadowPerformanceProvider provider =
                new ArtifactBackedExactModelShadowPerformanceProvider(
                        new ExactModelShadowPerformanceProperties(true, missingRoot.toString(), 524_288L),
                        new ObjectMapper(), new ExactModelShadowPerformanceValidator());

        assertThatThrownBy(() -> provider.find(selector("a".repeat(64))))
                .isInstanceOf(ExactModelShadowPerformanceUnavailableException.class);
    }

    private ArtifactBackedExactModelShadowPerformanceProvider provider() {
        return new ArtifactBackedExactModelShadowPerformanceProvider(
                new ExactModelShadowPerformanceProperties(true, root.toString(), 524_288L),
                new ObjectMapper(),
                new ExactModelShadowPerformanceValidator()
        );
    }

    private void copyFixture(Path destination) throws Exception {
        URI fixture = Objects.requireNonNull(getClass().getResource(
                "/fixtures/exact-model-shadow-performance/python-generated-v1/manifest.json")).toURI();
        Path source = Path.of(fixture).getParent();
        Files.createDirectory(destination);
        Files.copy(source.resolve("manifest.json"), destination.resolve("manifest.json"));
        Files.copy(
                source.resolve("exact_model_shadow_performance.json"),
                destination.resolve("exact_model_shadow_performance.json")
        );
    }

    private ExactModelShadowPerformanceSelector selector(String sha256) {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", sha256,
                "2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"
        );
    }
}
