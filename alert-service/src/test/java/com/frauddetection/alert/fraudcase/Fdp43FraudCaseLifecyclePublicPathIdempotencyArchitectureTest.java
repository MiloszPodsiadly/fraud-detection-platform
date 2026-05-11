package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp43FraudCaseLifecyclePublicPathIdempotencyArchitectureTest {

    @Test
    void fraudCaseControllerPostEndpointsMustUseIdempotencyKeyOverloads() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source)
                .contains("fraudCaseManagementService.createCase(request, idempotencyKey)")
                .contains("fraudCaseManagementService.assignCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.addNote(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.addDecision(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.transitionCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.closeCase(caseId, request, idempotencyKey)")
                .contains("fraudCaseManagementService.reopenCase(caseId, request, idempotencyKey)")
                .doesNotContain("fraudCaseManagementService.createCase(request)")
                .doesNotContain("fraudCaseManagementService.assignCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.addNote(caseId, request)")
                .doesNotContain("fraudCaseManagementService.addDecision(caseId, request)")
                .doesNotContain("fraudCaseManagementService.transitionCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.closeCase(caseId, request)")
                .doesNotContain("fraudCaseManagementService.reopenCase(caseId, request)");
    }

    @Test
    void fraudCaseControllerLifecycleHeadersMustBeRequired() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source).doesNotContain("@RequestHeader(name = \"X-Idempotency-Key\", required = false)");
        assertThat(Pattern.compile("@RequestHeader\\(name = \"X-Idempotency-Key\", required = true\\)")
                .matcher(source)
                .results()
                .count())
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void fraudCaseManagementIdempotencyOverloadsMustDelegateToLifecycleIdempotencyOverloads() {
        String source = read(sourceRoot().resolve(Path.of("service", "FraudCaseManagementService.java")));

        assertThat(source)
                .contains("return lifecycleService.createCase(request, idempotencyKey);")
                .contains("return lifecycleService.assignCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.addNote(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.addDecision(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.transitionCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.closeCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.reopenCase(caseId, request, idempotencyKey);");
    }

    @Test
    void compatibilityOverloadsMustBeMarkedDeprecatedAndInternalOnly() {
        String lifecycle = read(sourceRoot().resolve(Path.of("service", "FraudCaseLifecycleService.java")));
        String management = read(sourceRoot().resolve(Path.of("service", "FraudCaseManagementService.java")));

        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDocument createCase(CreateFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(lifecycle, "public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDocument createCase(CreateFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request)");
        assertDeprecatedCompatibilityOverload(management, "public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request)");
    }

    @Test
    void docsMustStatePublicLifecyclePostsRequireIdempotency() {
        String docs = compact(read(repoRoot().resolve(Path.of("docs", "api", "fraud-case-api.md")))
                + "\n"
                + read(repoRoot().resolve(Path.of("docs", "fdp-43-merge-gate.md"))));

        assertThat(docs)
                .contains("every local lifecycle `post` requires `x-idempotency-key`")
                .contains("all lifecycle post endpoints require `x-idempotency-key`");
    }

    private void assertDeprecatedCompatibilityOverload(String source, String signature) {
        int signatureIndex = source.indexOf(signature);
        assertThat(signatureIndex).as(signature).isGreaterThan(0);
        String prefix = source.substring(Math.max(0, signatureIndex - 400), signatureIndex);
        assertThat(prefix).as(signature)
                .contains("@Deprecated(forRemoval = false)")
                .contains("Internal/backward-compatibility path only");
        assertThat(compact(prefix)).as(signature)
                .contains("public http lifecycle post endpoints must use")
                .contains("idempotency-key overloads");
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
        if (Files.exists(Path.of("docs"))) {
            return Path.of(".");
        }
        return Path.of("..");
    }
}
