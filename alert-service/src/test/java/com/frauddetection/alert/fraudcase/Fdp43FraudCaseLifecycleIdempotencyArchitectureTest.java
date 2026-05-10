package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp43FraudCaseLifecycleIdempotencyArchitectureTest {

    @Test
    void localFraudCaseLifecycleIdempotencyMustNotUseRegulatedMutationCoordinator() {
        assertThat(read(sourceRoot().resolve(Path.of("service", "FraudCaseLifecycleService.java"))))
                .doesNotContain("RegulatedMutationCoordinator")
                .contains("FraudCaseLifecycleIdempotencyService");

        assertThat(read(sourceRoot().resolve(Path.of("fraudcase", "FraudCaseLifecycleIdempotencyService.java"))))
                .doesNotContain("RegulatedMutationCoordinator")
                .doesNotContain("RegulatedMutationCommandDocument")
                .contains("RegulatedMutationTransactionRunner");
    }

    @Test
    void lifecycleIdempotencyRecordShouldStoreHashesAndBoundedSnapshotOnly() {
        String source = read(sourceRoot().resolve(Path.of("persistence", "FraudCaseLifecycleIdempotencyRecordDocument.java")));

        assertThat(source)
                .contains("idempotencyKeyHash")
                .contains("requestHash")
                .contains("responsePayloadSnapshot")
                .doesNotContain("private String idempotencyKey;")
                .doesNotContain("private String requestPayload")
                .doesNotContain("private String raw")
                .doesNotContain("RegulatedMutationCommandDocument");
    }

    @Test
    void regulatedHasherAndConflictPolicyShouldDelegateToSharedPrimitives() {
        String hasher = read(sourceRoot().resolve(Path.of("regulated", "RegulatedMutationIntentHasher.java")));
        String regulatedPolicy = read(sourceRoot().resolve(Path.of("regulated", "RegulatedMutationConflictPolicy.java")));
        String lifecyclePolicy = read(sourceRoot().resolve(Path.of("fraudcase", "FraudCaseLifecycleIdempotencyConflictPolicy.java")));

        assertThat(hasher)
                .contains("IdempotencyCanonicalHasher")
                .doesNotContain("MessageDigest");
        assertThat(regulatedPolicy).contains("SharedIdempotencyConflictPolicy");
        assertThat(lifecyclePolicy).contains("SharedIdempotencyConflictPolicy");
    }

    @Test
    void fraudCaseLifecyclePackageShouldNotCreateDuplicateHashingHelpers() throws IOException {
        Path fraudCaseRoot = sourceRoot().resolve("fraudcase");

        try (var files = Files.walk(fraudCaseRoot)) {
            assertThat(files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        String source = read(path);
                        return source.contains("MessageDigest") || source.contains("SHA-256");
                    })
                    .toList())
                    .isEmpty();
        }
    }

    @Test
    void openApiShouldDocumentFdp43LifecycleIdempotencyContract() {
        String openApi = compact(read(repoRoot().resolve(Path.of("docs", "openapi", "alert-service.openapi.yaml"))));

        assertThat(openApi)
                .contains("x-idempotency-key")
                .contains("same key with the same payload")
                .contains("same key with a different claim returns 409")
                .contains("not implemented through regulatedmutationcoordinator")
                .contains("is not fdp-29")
                .contains("is not lease fenced")
                .contains("is not global exactly-once")
                .contains("does not provide external finality")
                .doesNotContain("this endpoint is not idempotent unless a future");
    }

    @Test
    void fdp43DocsShouldStateRequiredGuaranteesAndNoGoClaims() {
        String docs = read(repoRoot().resolve(Path.of("docs", "fdp-43-merge-gate.md"))).toLowerCase(java.util.Locale.ROOT);

        assertThat(docs)
                .contains("shared canonical hashing and key validation")
                .contains("x-idempotency-key")
                .contains("stable replay response")
                .contains("raw idempotency keys and raw request payloads are not stored")
                .contains("must not use `regulatedmutationcoordinator`")
                .contains("must not introduce lease fencing")
                .contains("external finality")
                .contains("must not create unrelated duplicate sha-256");
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    private String compact(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }

    private Path repoRoot() {
        Path moduleRoot = Path.of("pom.xml");
        if (Files.exists(moduleRoot.resolveSibling("docs"))) {
            return Path.of(".");
        }
        return Path.of("..");
    }
}
