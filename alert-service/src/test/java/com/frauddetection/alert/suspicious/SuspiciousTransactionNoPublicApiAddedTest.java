package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionNoPublicApiAddedTest {

    @Test
    void noRestControllerExposesSuspiciousTransactionEndpoint() throws IOException {
        Path controllerRoot = Path.of("src/main/java/com/frauddetection/alert/controller");

        try (var paths = Files.walk(controllerRoot)) {
            List<Path> controllers = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            assertThat(controllers).allSatisfy(path -> {
                String source = read(path);
                if (source.contains("suspicious-transactions")) {
                    assertThat(source).doesNotContain("@RestController", "@RequestMapping");
                }
            });
        }
    }

    @Test
    void suspiciousPackageDoesNotDeclarePublicApiController() {
        assertThat(SuspiciousTransactionDocument.class.getPackageName()).doesNotContain(".api");
        assertThat(SuspiciousTransactionProjectionService.class.getAnnotation(RestController.class)).isNull();
        assertThat(SuspiciousTransactionProjectionService.class.getAnnotation(RequestMapping.class)).isNull();
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
