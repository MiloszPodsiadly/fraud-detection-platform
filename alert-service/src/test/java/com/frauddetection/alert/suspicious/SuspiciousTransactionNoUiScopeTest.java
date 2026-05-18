package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionNoUiScopeTest {

    @Test
    void analystConsoleDoesNotContainSuspiciousTransactionFdp60Surface() throws IOException {
        Path uiRoot = Path.of("../analyst-console-ui/src");
        if (!Files.exists(uiRoot)) {
            return;
        }

        try (var paths = Files.walk(uiRoot)) {
            assertThat(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js") || path.toString().endsWith(".jsx"))
                    .filter(path -> containsSuspiciousTransactionText(path))
                    .toList())
                    .isEmpty();
        }
    }

    private boolean containsSuspiciousTransactionText(Path path) {
        try {
            String source = Files.readString(path).toLowerCase(java.util.Locale.ROOT);
            return source.contains("suspicious transaction") || source.contains("suspicious-transactions");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
