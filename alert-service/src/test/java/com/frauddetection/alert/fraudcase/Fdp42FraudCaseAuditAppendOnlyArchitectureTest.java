package com.frauddetection.alert.fraudcase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
                    .doesNotContain("FraudCaseAuditRepository.delete")
                    .doesNotContain("MongoTemplate.remove");
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
