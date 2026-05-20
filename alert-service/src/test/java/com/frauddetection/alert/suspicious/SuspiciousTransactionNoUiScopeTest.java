package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionNoUiScopeTest {

    @Test
    void analystConsoleSuspiciousTransactionSurfaceMustRemainFdp66ReadOnly() throws IOException {
        Path uiRoot = Path.of("../analyst-console-ui/src");
        if (!Files.exists(uiRoot)) {
            return;
        }

        var suspiciousTransactionUiFiles = suspiciousTransactionUiFiles(uiRoot);
        if (suspiciousTransactionUiFiles.isEmpty()) {
            return;
        }

        String docs = Files.readString(Path.of("../docs/product/suspicious_transaction_internal_ui.md"));
        assertThat(docs)
                .contains("FDP-66 internal read-only UI")
                .contains("SUSPICIOUS_TRANSACTION_READ")
                .contains("Backend authorization remains authoritative")
                .contains("No write endpoint")
                .contains("No confirm, dismiss, submit, link-case, assign, claim, export, or bulk action");

        assertThat(suspiciousTransactionUiFiles)
                .allSatisfy(path -> assertThat(containsMutationText(path)).as(path.toString()).isFalse());
    }

    private java.util.List<Path> suspiciousTransactionUiFiles(Path uiRoot) throws IOException {
        try (var paths = Files.walk(uiRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js") || path.toString().endsWith(".jsx"))
                    .filter(path -> containsSuspiciousTransactionText(path))
                    .toList();
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

    private boolean containsMutationText(Path path) {
        try {
            String source = Files.readString(path).toLowerCase(java.util.Locale.ROOT);
            return source.contains("confirm suspicious")
                    || source.contains("dismiss suspicious")
                    || source.contains("submit suspicious")
                    || source.contains("assign suspicious")
                    || source.contains("claim suspicious")
                    || source.contains("export suspicious")
                    || source.contains("confirmSuspicious")
                    || source.contains("dismissSuspicious")
                    || source.contains("submitSuspicious")
                    || source.contains("assignSuspicious")
                    || source.contains("claimSuspicious")
                    || source.contains("exportSuspicious");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
