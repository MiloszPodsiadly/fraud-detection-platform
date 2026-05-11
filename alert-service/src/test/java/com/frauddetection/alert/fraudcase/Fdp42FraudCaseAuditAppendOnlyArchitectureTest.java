package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp42FraudCaseAuditAppendOnlyArchitectureTest {

    @Test
    void fraudCaseAuditServiceShouldExposeAppendOnlyApi() {
        assertThat(Arrays.stream(FraudCaseAuditService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList())
                .extracting(java.lang.reflect.Method::getName)
                .contains("append")
                .doesNotContain("update", "delete", "replace", "truncate");
    }

    @Test
    void productionCodeShouldNotMutateOrDeleteFraudCaseAuditEntriesOutsideAppend() throws IOException {
        Path sourceRoot = sourceRoot();

        try (var files = Files.walk(sourceRoot)) {
            String source = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("FraudCaseAuditService.java")))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(source)
                    .doesNotContain("auditRepository.delete")
                    .doesNotContain("auditRepository.save")
                    .doesNotContain("FraudCaseAuditRepository.save")
                    .doesNotContain("FraudCaseAuditRepository.delete")
                    .doesNotContain("MongoTemplate.remove");
        }
    }

    @Test
    void controllersShouldNotExposeAuditEntryMutationEndpoints() throws IOException {
        Path controller = sourceRoot().resolve(Path.of("controller", "FraudCaseController.java"));
        String source = read(controller);

        assertThat(source).doesNotContain("@PutMapping(\"/{caseId}/audit");
        assertThat(source).doesNotContain("@PatchMapping(\"/{caseId}/audit");
        assertThat(source).doesNotContain("@DeleteMapping(\"/{caseId}/audit");
        assertThat(source).doesNotContain("@PutMapping(\"/{caseId}/audit/");
        assertThat(source).doesNotContain("@PatchMapping(\"/{caseId}/audit/");
        assertThat(source).doesNotContain("@DeleteMapping(\"/{caseId}/audit/");
    }

    @Test
    void productionServicesShouldNotPersistAuditEntriesDirectly() throws IOException {
        Path sourceRoot = sourceRoot();

        try (var files = Files.walk(sourceRoot)) {
            List<Path> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("FraudCaseAuditService.java")))
                    .filter(path -> read(path).lines()
                            .anyMatch(line -> line.contains("FraudCaseAuditEntryDocument") && line.contains(".save(")))
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    @Test
    void fraudCaseManagementServiceShouldUseInjectedLifecycleAndQueryServices() {
        String source = read(sourceRoot().resolve(Path.of("service", "FraudCaseManagementService.java")));

        assertThat(source)
                .contains("FraudCaseLifecycleService lifecycleService")
                .contains("FraudCaseQueryService queryService")
                .doesNotContain("new FraudCaseLifecycleService")
                .doesNotContain("new FraudCaseQueryService");
    }

    @Test
    void fraudCaseManagementServiceShouldDelegateLifecycleAndQueryOperations() {
        String source = read(sourceRoot().resolve(Path.of("service", "FraudCaseManagementService.java")));

        assertThat(source)
                .contains("return queryService.listCases();")
                .contains("return queryService.listCases(pageable);")
                .contains("return queryService.getCase(caseId);")
                .contains("return queryService.searchCases(status, assignee, priority, riskLevel, createdFrom, createdTo, linkedAlertId, pageable);")
                .contains("return queryService.auditTrail(caseId);")
                .contains("return lifecycleService.createCase(request, idempotencyKey);")
                .contains("return lifecycleService.assignCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.addNote(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.addDecision(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.transitionCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.closeCase(caseId, request, idempotencyKey);")
                .contains("return lifecycleService.reopenCase(caseId, request, idempotencyKey);")
                .doesNotContain("noteRepository.save")
                .doesNotContain("decisionRepository.save")
                .doesNotContain("auditRepository.save");
    }

    @Test
    void fraudCaseQueryServiceShouldRemainReadOnly() {
        String source = read(sourceRoot().resolve(Path.of("service", "FraudCaseQueryService.java")));

        assertThat(source)
                .doesNotContain(".save(")
                .doesNotContain(".insert(")
                .doesNotContain(".update")
                .doesNotContain(".remove(")
                .doesNotContain("MongoTemplate");
    }

    @Test
    void fraudCaseControllerShouldNotContainLifecyclePolicyOrSystemIngestion() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source)
                .doesNotContain("FraudCaseStatus.")
                .doesNotContain("FraudCaseLifecycleService")
                .doesNotContain("FraudCaseTransitionPolicy")
                .doesNotContain("handleScoredTransaction");
    }

    @Test
    void fraudCaseControllerShouldUsePagedListOnly() {
        String source = read(sourceRoot().resolve(Path.of("controller", "FraudCaseController.java")));

        assertThat(source)
                .contains("PageRequest.of(page, size")
                .contains("fraudCaseManagementService.listCases(pageable)")
                .doesNotContain("fraudCaseManagementService.listCases()");
    }

    @Test
    void fraudCaseRepositoriesShouldNotContainLifecyclePolicyLogic() throws IOException {
        Path persistenceRoot = sourceRoot().resolve("persistence");

        try (var files = Files.walk(persistenceRoot)) {
            List<Path> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Repository.java"))
                    .filter(path -> {
                        String source = read(path);
                        return source.contains("FraudCaseTransitionPolicy")
                                || source.contains("validateTransition")
                                || source.contains("ALLOWED_TRANSITIONS")
                                || source.contains("allowedTransitions")
                                || source.contains("FraudCaseStatus.");
                    })
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    @Test
    void fraudCaseTransitionPolicyShouldOwnAllowedTransitionTable() throws IOException {
        Path sourceRoot = sourceRoot();

        try (var files = Files.walk(sourceRoot)) {
            List<Path> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("FraudCaseTransitionPolicy.java")))
                    .filter(path -> read(path).contains("ALLOWED_TRANSITIONS") || read(path).contains("allowedTransitions()"))
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    private Path sourceRoot() {
        Path moduleRoot = Path.of("src", "main", "java", "com", "frauddetection", "alert");
        if (Files.exists(moduleRoot)) {
            return moduleRoot;
        }
        return Path.of("alert-service", "src", "main", "java", "com", "frauddetection", "alert");
    }
}
