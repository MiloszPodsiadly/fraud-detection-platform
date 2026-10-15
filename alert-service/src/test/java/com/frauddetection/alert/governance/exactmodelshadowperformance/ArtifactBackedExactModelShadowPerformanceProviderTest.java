package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.governance.artifact.GovernanceArtifactReader;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelcard.ModelCardValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    void rehashedSemanticDriftAndRawRecordsFailClosed() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                summary -> ((ObjectNode) summary.at(
                        "/evaluationSummary/supportedMetrics/rulesVsMlDisagreement"))
                        .put("unexpectedNestedEvidence", true),
                summary -> ((ObjectNode) summary.at(
                        "/evaluationSummary/supportedMetrics/rulesVsMlDisagreement/categoryCounts"))
                        .put("BOTH_LOW", 2),
                summary -> ((ObjectNode) summary.at("/evaluationSummary/evaluationSubject"))
                        .put("modelArtifactSha256", "b".repeat(64)),
                summary -> ((ObjectNode) summary.at("/evaluationSummary/evaluationWindow"))
                        .put("fromInclusive", "2026-06-02T00:00:00Z"),
                summary -> ((ObjectNode) summary.get("governance"))
                        .put("runtimeDecisionAuthority", "ML"),
                summary -> summary.putArray("rawRecords").addObject().put("transactionReference", "forbidden")
        );
        for (int index = 0; index < mutations.size(); index++) {
            ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
            Path destination = root.resolve("case-" + index).resolve(selector.selectionKey());
            Files.createDirectories(destination.getParent());
            copyFixture(destination);
            rewriteCanonicalArtifact(destination, mutations.get(index));

            ArtifactBackedExactModelShadowPerformanceProvider provider = provider(destination.getParent());
            assertThatThrownBy(() -> provider.find(selector))
                    .isInstanceOf(ExactModelShadowPerformanceUnavailableException.class);
        }
    }

    @Test
    void unexpectedFilesAndRehashedNonCanonicalSummaryFailClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path destination = root.resolve(selector.selectionKey());
        copyFixture(destination);
        Files.writeString(destination.resolve("unexpected.json"), "{}", StandardCharsets.UTF_8);

        assertUnavailable(provider(), selector);

        Files.delete(destination.resolve("unexpected.json"));
        Path summaryPath = destination.resolve("exact_model_shadow_performance.json");
        byte[] canonical = Files.readAllBytes(summaryPath);
        Files.writeString(summaryPath, "  " + new String(canonical, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        rewriteManifestDigest(destination);

        assertUnavailable(provider(), selector);
    }

    @Test
    void oversizedSummaryAndArtifactChangedBetweenReadsFailClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path destination = root.resolve(selector.selectionKey());
        copyFixture(destination);

        assertUnavailable(provider(root, 32L, new GovernanceArtifactReader()), selector);

        AtomicInteger reads = new AtomicInteger();
        GovernanceArtifactReader changingReader = new GovernanceArtifactReader(path -> {
            if (reads.incrementAndGet() == 3) {
                try {
                    Files.writeString(
                            destination.resolve("exact_model_shadow_performance.json"),
                            "changed between reads\n",
                            StandardCharsets.UTF_8
                    );
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        });
        assertUnavailable(provider(root, 524_288L, changingReader), selector);
    }

    @Test
    void symbolicLinkArtifactDirectoryFailsClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path external = root.resolve("external");
        copyFixture(external);
        Path linked = root.resolve(selector.selectionKey());
        try {
            Files.createSymbolicLink(linked, external);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
        }

        assertUnavailable(provider(), selector);
    }

    @Test
    void configuredMissingArtifactRootIsUnavailableRatherThanNotFound() {
        Path missingRoot = root.resolve("missing-root");
        ArtifactBackedExactModelShadowPerformanceProvider provider =
                new ArtifactBackedExactModelShadowPerformanceProvider(
                        new ExactModelShadowPerformanceProperties(true, missingRoot.toString(), 524_288L),
                        new ObjectMapper(), new ExactModelShadowPerformanceValidator(
                                new ModelCardValidator()));

        assertThatThrownBy(() -> provider.find(selector("a".repeat(64))))
                .isInstanceOf(ExactModelShadowPerformanceUnavailableException.class);
    }

    private ArtifactBackedExactModelShadowPerformanceProvider provider() {
        return provider(root);
    }

    private ArtifactBackedExactModelShadowPerformanceProvider provider(Path artifactRoot) {
        return provider(artifactRoot, 524_288L, new GovernanceArtifactReader());
    }

    private ArtifactBackedExactModelShadowPerformanceProvider provider(
            Path artifactRoot,
            long maxSummarySizeBytes,
            GovernanceArtifactReader artifactReader
    ) {
        return new ArtifactBackedExactModelShadowPerformanceProvider(
                new ExactModelShadowPerformanceProperties(true, artifactRoot.toString(), maxSummarySizeBytes),
                new ObjectMapper(),
                new ExactModelShadowPerformanceValidator(
                        new ModelCardValidator()),
                artifactReader
        );
    }

    private void assertUnavailable(
            ArtifactBackedExactModelShadowPerformanceProvider provider,
            ExactModelShadowPerformanceSelector selector
    ) {
        assertThatThrownBy(() -> provider.find(selector))
                .isInstanceOf(ExactModelShadowPerformanceUnavailableException.class);
    }

    private void rewriteCanonicalArtifact(Path destination, Consumer<ObjectNode> mutation) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path summaryPath = destination.resolve("exact_model_shadow_performance.json");
        ObjectNode summary = (ObjectNode) mapper.readTree(Files.readAllBytes(summaryPath));
        mutation.accept(summary);
        byte[] summaryPayload = GovernanceCanonicalJson.serialize(mapper, summary);
        Files.write(summaryPath, summaryPayload);
        rewriteManifestDigest(destination);
    }

    private void rewriteManifestDigest(Path destination) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] summaryPayload = Files.readAllBytes(destination.resolve("exact_model_shadow_performance.json"));
        Path manifestPath = destination.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        ObjectNode file = (ObjectNode) manifest.at("/files/0");
        file.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(summaryPayload)));
        file.put("sizeBytes", summaryPayload.length);
        Files.write(manifestPath, GovernanceCanonicalJson.serialize(mapper, manifest));
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
