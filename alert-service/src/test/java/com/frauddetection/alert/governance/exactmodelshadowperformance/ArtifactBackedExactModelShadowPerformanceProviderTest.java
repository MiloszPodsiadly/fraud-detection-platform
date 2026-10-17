package com.frauddetection.alert.governance.exactmodelshadowperformance;

import com.frauddetection.alert.governance.artifact.GovernanceArtifactReader;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelevaluation.ModelEvaluationGovernanceValidator;
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

    private static final String SUMMARY_FILENAME = "exact_model_shadow_performance.json";
    private static final String MANIFEST_FILENAME = "manifest.json";

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
    void exposesDigestOfTheVerifiedExactArtifactManifest() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        copyFixture(root.resolve(selector.selectionKey()));

        VerifiedExactModelShadowPerformanceArtifact artifact = provider().findArtifact(selector).orElseThrow();

        assertThat(artifact.manifestSha256()).matches("^[0-9a-f]{64}$");
        assertThat(artifact.summary().at("/evaluationSummary/evaluationSubject/modelArtifactSha256").textValue())
                .isEqualTo("a".repeat(64));
    }

    @Test
    void readsPythonGeneratedClaimOnlyArtifactWithoutUpgradingProvenance() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        copyFixture(root.resolve(selector.selectionKey()), "python-generated-claim-only-v1");

        JsonNode result = provider().find(selector).orElseThrow();

        assertThat(result.at("/sourceEvaluation/sourceDatasetVerificationLevel").textValue())
                .isEqualTo("CLAIM_ONLY");
        assertThat(result.at("/sourceEvaluation/externalAttestation").textValue())
                .isEqualTo("NOT_PROVIDED");
        assertThat(result.at("/sourceEvaluation/immutableStorageGuarantee").textValue())
                .isEqualTo("NOT_CLAIMED");
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
                summary -> ((ObjectNode) summary.get("governance"))
                        .put("runtimeDecisionAuthority", "ML"),
                summary -> {
                    ObjectNode source = (ObjectNode) summary.get("sourceEvaluation");
                    source.put("externalAttestation", "PROVIDED");
                    source.put("immutableStorageGuarantee", "VERIFIED");
                },
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
    void exactIdentityAndWindowMismatchFailClosedAfterRehash() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                summary -> ((ObjectNode) summary.at("/evaluationSummary/evaluationSubject"))
                        .put("modelArtifactSha256", "b".repeat(64)),
                summary -> ((ObjectNode) summary.at("/evaluationSummary/evaluationWindow"))
                        .put("fromInclusive", "2026-06-02T00:00:00Z")
        );
        for (int index = 0; index < mutations.size(); index++) {
            ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
            Path destination = root.resolve("selector-mismatch-" + index).resolve(selector.selectionKey());
            Files.createDirectories(destination.getParent());
            copyFixture(destination);
            rewriteCanonicalArtifact(destination, mutations.get(index));

            assertUnavailable(provider(destination.getParent()), selector);
        }
    }

    @Test
    void malformedOversizedAndNonCanonicalManifestFailClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));

        Path malformed = root.resolve("malformed").resolve(selector.selectionKey());
        Files.createDirectories(malformed.getParent());
        copyFixture(malformed);
        Files.writeString(malformed.resolve(MANIFEST_FILENAME), "{\n", StandardCharsets.UTF_8);
        assertUnavailable(provider(malformed.getParent()), selector);

        Path oversized = root.resolve("oversized").resolve(selector.selectionKey());
        Files.createDirectories(oversized.getParent());
        copyFixture(oversized);
        Files.writeString(oversized.resolve(MANIFEST_FILENAME), "x".repeat(65_537), StandardCharsets.UTF_8);
        assertUnavailable(provider(oversized.getParent()), selector);

        Path nonCanonical = root.resolve("noncanonical").resolve(selector.selectionKey());
        Files.createDirectories(nonCanonical.getParent());
        copyFixture(nonCanonical);
        Path manifest = nonCanonical.resolve(MANIFEST_FILENAME);
        Files.writeString(manifest, " " + Files.readString(manifest), StandardCharsets.UTF_8);
        assertUnavailable(provider(nonCanonical.getParent()), selector);
    }

    @Test
    void unexpectedFilesAndRehashedNonCanonicalSummaryFailClosed() throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path destination = root.resolve(selector.selectionKey());
        copyFixture(destination);
        Files.writeString(destination.resolve("unexpected.json"), "{}", StandardCharsets.UTF_8);

        assertUnavailable(provider(), selector);

        Files.delete(destination.resolve("unexpected.json"));
        Path summaryPath = destination.resolve(SUMMARY_FILENAME);
        byte[] canonical = Files.readAllBytes(summaryPath);
        Files.writeString(summaryPath, "  " + new String(canonical, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        rewriteManifestDigest(destination);

        assertUnavailable(provider(), selector);
    }

    @Test
    void symbolicLinkSummaryAndManifestFilesFailClosed() throws Exception {
        for (String filename : List.of(SUMMARY_FILENAME, MANIFEST_FILENAME)) {
            ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
            Path caseRoot = root.resolve("linked-" + filename.replace('.', '-'));
            Path destination = caseRoot.resolve(selector.selectionKey());
            Files.createDirectories(caseRoot);
            copyFixture(destination);
            Path linked = destination.resolve(filename);
            Path external = caseRoot.resolve("external-" + filename);
            Files.move(linked, external);
            try {
                Files.createSymbolicLink(linked, external);
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
                Assumptions.assumeTrue(false, "symbolic links unavailable: " + exception.getMessage());
            }

            assertUnavailable(provider(caseRoot), selector);
        }
    }

    @Test
    void manifestAndSummaryChangesBetweenStableReadPassesFailClosed() throws Exception {
        assertChangedBetweenPassesFailsClosed(true);
        assertChangedBetweenPassesFailsClosed(false);
    }

    @Test
    void corruptSelectionDoesNotFallBackToValidNeighbor() throws Exception {
        ExactModelShadowPerformanceSelector selected = selector("a".repeat(64));
        ExactModelShadowPerformanceSelector neighbor = selector("b".repeat(64));
        Path selectedDirectory = root.resolve(selected.selectionKey());
        Path neighborDirectory = root.resolve(neighbor.selectionKey());
        copyFixture(selectedDirectory);
        copyFixture(neighborDirectory);
        rewriteCanonicalArtifact(neighborDirectory, summary ->
                ((ObjectNode) summary.at("/evaluationSummary/evaluationSubject"))
                        .put("modelArtifactSha256", "b".repeat(64)));
        rewriteManifestSelectionKey(neighborDirectory, neighbor.selectionKey());
        Files.writeString(selectedDirectory.resolve(SUMMARY_FILENAME), "corrupt\n", StandardCharsets.UTF_8);

        assertThat(provider().find(neighbor)).isPresent();
        assertUnavailable(provider(), selected);
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
                                new ModelEvaluationGovernanceValidator()));

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
                        new ModelEvaluationGovernanceValidator()),
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
        Path summaryPath = destination.resolve(SUMMARY_FILENAME);
        ObjectNode summary = (ObjectNode) mapper.readTree(Files.readAllBytes(summaryPath));
        mutation.accept(summary);
        byte[] summaryPayload = GovernanceCanonicalJson.serialize(mapper, summary);
        Files.write(summaryPath, summaryPayload);
        rewriteManifestDigest(destination);
    }

    private void rewriteManifestDigest(Path destination) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] summaryPayload = Files.readAllBytes(destination.resolve(SUMMARY_FILENAME));
        Path manifestPath = destination.resolve(MANIFEST_FILENAME);
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        ObjectNode file = (ObjectNode) manifest.at("/files/0");
        file.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(summaryPayload)));
        file.put("sizeBytes", summaryPayload.length);
        Files.write(manifestPath, GovernanceCanonicalJson.serialize(mapper, manifest));
    }

    private void rewriteManifestSelectionKey(Path destination, String selectionKey) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path manifestPath = destination.resolve(MANIFEST_FILENAME);
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        manifest.put("selectionKey", selectionKey);
        Files.write(manifestPath, GovernanceCanonicalJson.serialize(mapper, manifest));
    }

    private void assertChangedBetweenPassesFailsClosed(boolean changeManifest) throws Exception {
        ExactModelShadowPerformanceSelector selector = selector("a".repeat(64));
        Path caseRoot = root.resolve(changeManifest ? "changing-manifest" : "changing-summary");
        Path destination = caseRoot.resolve(selector.selectionKey());
        Files.createDirectories(caseRoot);
        copyFixture(destination);
        AtomicInteger manifestReads = new AtomicInteger();
        AtomicInteger summaryReads = new AtomicInteger();
        GovernanceArtifactReader changingReader = new GovernanceArtifactReader(path -> {
            try {
                if (changeManifest && path.getFileName().toString().equals(SUMMARY_FILENAME)
                        && summaryReads.incrementAndGet() == 1) {
                    Path manifest = destination.resolve(MANIFEST_FILENAME);
                    Files.writeString(manifest, " " + Files.readString(manifest), StandardCharsets.UTF_8);
                } else if (!changeManifest && path.getFileName().toString().equals(MANIFEST_FILENAME)
                        && manifestReads.incrementAndGet() == 2) {
                    Files.writeString(destination.resolve(SUMMARY_FILENAME), "changed\n", StandardCharsets.UTF_8);
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertUnavailable(provider(caseRoot, 524_288L, changingReader), selector);
    }

    private void copyFixture(Path destination) throws Exception {
        copyFixture(destination, "python-generated-v1");
    }

    private void copyFixture(Path destination, String fixtureName) throws Exception {
        URI fixture = Objects.requireNonNull(getClass().getResource(
                "/fixtures/exact-model-shadow-performance/" + fixtureName + "/" + MANIFEST_FILENAME)).toURI();
        Path source = Path.of(fixture).getParent();
        Files.createDirectory(destination);
        Files.copy(source.resolve(MANIFEST_FILENAME), destination.resolve(MANIFEST_FILENAME));
        Files.copy(
                source.resolve(SUMMARY_FILENAME),
                destination.resolve(SUMMARY_FILENAME)
        );
    }

    private ExactModelShadowPerformanceSelector selector(String sha256) {
        return new ExactModelShadowPerformanceSelector(
                "python-logistic-fraud-model", "2026-10-15.shadow", "feature-contract-v2", sha256,
                "2026-06-01T00:00:00Z", "2026-06-09T23:59:59Z"
        );
    }
}
