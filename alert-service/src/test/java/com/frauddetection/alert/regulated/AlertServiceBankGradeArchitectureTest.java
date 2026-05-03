package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceBankGradeArchitectureTest {

    @Test
    void bankProfileMustDeclareRequiredBankGradeSettings() throws Exception {
        String bank = Files.readString(Path.of("src/main/resources/application-bank.yml"));

        assertThat(bank).contains(
                "transaction-mode: REQUIRED",
                "refresh-mode: ATOMIC",
                "fail-closed: true",
                "dual-control:",
                "enabled: true"
        );
    }

    @Test
    void regulatedMutationHandlersMustNotPublishOrAuditSuccessDirectly() throws Exception {
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert/regulated/mutation"))) {
            List<Path> handlers = stream.filter(path -> path.toString().endsWith(".java")).toList();
            for (Path handler : handlers) {
                String source = Files.readString(handler);
                assertThat(source).as(handler.toString())
                        .doesNotContain("KafkaTemplate")
                        .doesNotContain("AuditOutcome.SUCCESS")
                        .doesNotContain("auditService.audit")
                        .doesNotContain("ExternalAuditAnchor");
            }
        }
    }

    @Test
    void operationalControllersMustNotUseRepositoryFindAllDirectly() throws Exception {
        List<Path> controllers;
        try (java.util.stream.Stream<Path> stream = Files.walk(Path.of("src/main/java/com/frauddetection/alert"))) {
            controllers = stream
                    .filter(path -> path.toString().endsWith("Controller.java"))
                    .toList();
        }
        for (Path controller : controllers) {
            String source = Files.readString(controller);
            assertThat(source).as(controller.toString())
                    .doesNotContain(".findAll()");
        }
    }

    @Test
    void docsMustNotOverclaimBankGradeClosure() throws Exception {
        String combined = Files.readString(Path.of("../README.md"))
                + "\n" + readIfExists("../docs/architecture/alert-service-source-of-truth.md")
                + "\n" + readIfExists("../docs/FDP-27-merge-gate.md");

        assertContextual(combined, "distributed ACID");
        assertContextual(combined, "exactly-once");
        assertContextual(combined, "WORM");
        assertContextual(combined, "legal notarization");
        assertContextual(combined, "regulator-certified");
        assertContextual(combined, "no mutation before evidence");
        assertThat(combined).contains("does not provide distributed ACID");
        assertThat(combined).contains("does not provide exactly-once Kafka delivery");
    }

    private String readIfExists(String path) throws Exception {
        Path resolved = Path.of(path);
        return Files.exists(resolved) ? Files.readString(resolved) : "";
    }

    private void assertContextual(String source, String phrase) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        String needle = phrase.toLowerCase(java.util.Locale.ROOT);
        int index = lower.indexOf(needle);
        while (index >= 0) {
            int start = Math.max(0, index - 260);
            int end = Math.min(lower.length(), index + needle.length() + 260);
            assertThat(lower.substring(start, end))
                    .as("Forbidden wording must be negated or limitation-contextual: " + phrase)
                    .containsAnyOf("does not", "not ", "no ", "outside", "future", "deferred", "limitation");
            index = lower.indexOf(needle, index + needle.length());
        }
    }
}
