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
