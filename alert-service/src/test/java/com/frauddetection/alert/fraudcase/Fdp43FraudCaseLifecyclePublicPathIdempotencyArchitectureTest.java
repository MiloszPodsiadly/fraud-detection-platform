package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp43FraudCaseLifecyclePublicPathIdempotencyArchitectureTest {

    @Test
    void fraudCaseControllerCurrentPatchEndpointMustUseIdempotencyKeyOverload() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source)
                .contains("fraudCaseManagementService.updateCase(caseId, request, idempotencyKey)")
                .doesNotContain("fraudCaseManagementService.createCase(")
                .doesNotContain("fraudCaseManagementService.assignCase(")
                .doesNotContain("fraudCaseManagementService.addNote(")
                .doesNotContain("fraudCaseManagementService.addDecision(")
                .doesNotContain("fraudCaseManagementService.transitionCase(")
                .doesNotContain("fraudCaseManagementService.closeCase(")
                .doesNotContain("fraudCaseManagementService.reopenCase(")
                .doesNotContain("fraudCaseManagementService.auditTrail(");
    }

    @Test
    void fraudCaseControllerCurrentMutationHeaderMustBeRequired() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source).doesNotContain("@RequestHeader(name = \"X-Idempotency-Key\", required = false)");
        assertThat(Pattern.compile("@RequestHeader\\(name = \"X-Idempotency-Key\", required = true\\)")
                .matcher(source)
                .results()
                .count())
                .isEqualTo(1);
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
    void publicCompatibilityOverloadsMustNotRemainOnLifecycleMutationServices() {
        String lifecycle = read(sourceRoot().resolve(Path.of("service", "FraudCaseLifecycleService.java")));
        String management = read(sourceRoot().resolve(Path.of("service", "FraudCaseManagementService.java")));

        assertNoPublicNoKeyLifecycleOverloads(lifecycle);
        assertNoPublicNoKeyLifecycleOverloads(management);
        assertThat(lifecycle).doesNotContain("public UpdateFraudCaseResponse updateCase(String caseId, UpdateFraudCaseRequest request)");
        assertThat(management).doesNotContain("public UpdateFraudCaseResponse updateCase(String caseId, UpdateFraudCaseRequest request)");
    }

    @Test
    void docsMustStateCurrentPatchRequiresIdempotency() {
        String docs = compact(read(repoRoot().resolve(Path.of("docs", "api", "fraud_case_api.md"))));

        assertThat(docs)
                .contains("`patch`")
                .contains("`x-idempotency-key`")
                .contains("intentional api surface cleanup");
    }

    private void assertNoPublicNoKeyLifecycleOverloads(String source) {
        assertThat(source)
                .doesNotContain("public FraudCaseDocument createCase(CreateFraudCaseRequest request)")
                .doesNotContain("public FraudCaseDocument assignCase(String caseId, AssignFraudCaseRequest request)")
                .doesNotContain("public FraudCaseNoteResponse addNote(String caseId, AddFraudCaseNoteRequest request)")
                .doesNotContain("public FraudCaseDecisionResponse addDecision(String caseId, AddFraudCaseDecisionRequest request)")
                .doesNotContain("public FraudCaseDocument transitionCase(String caseId, TransitionFraudCaseRequest request)")
                .doesNotContain("public FraudCaseDocument closeCase(String caseId, CloseFraudCaseRequest request)")
                .doesNotContain("public FraudCaseDocument reopenCase(String caseId, ReopenFraudCaseRequest request)");
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
