package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class Fdp40ReleaseControlsSupport {

    static final Path OUTPUT_DIR = Path.of("target", "fdp40-release");
    static final Path DOCS = Path.of("..", "docs");
    static final String RELEASE_DIGEST = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    static final String FIXTURE_DIGEST = "sha256:3333333333333333333333333333333333333333333333333333333333333333";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> FORBIDDEN_FALLBACK_TOKENS = List.of(
            "LOCAL_",
            "PLACEHOLDER",
            "TO_BE_FILLED",
            "UNKNOWN",
            "NOT_PROVIDED"
    );

    private Fdp40ReleaseControlsSupport() {
    }

    static Map<String, String> readYamlKeyValues(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#") || !trimmed.contains(":")) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            values.put(parts[0].trim(), stripQuotes(parts[1].trim()));
        }
        return values;
    }

    static Map<String, Object> readJson(Path path) throws IOException {
        return OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<>() {
        });
    }

    static ObjectNode objectNode() {
        return OBJECT_MAPPER.createObjectNode();
    }

    static void writeJson(Path path, Object object) throws IOException {
        Files.createDirectories(path.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), object);
    }

    static void assertNoFallbackTokens(String value) {
        for (String token : FORBIDDEN_FALLBACK_TOKENS) {
            assertThat(value).doesNotContain(token);
        }
    }

    static void assertSha256(String value) {
        assertThat(value).startsWith("sha256:");
    }

    static void assertReleaseManifestValid(Map<String, String> manifest) {
        assertThat(manifest).containsKeys(
                "release_manifest_version",
                "commit_sha",
                "branch_name",
                "github_run_id",
                "workflow_name",
                "release_image_name",
                "release_image_tag",
                "release_image_digest",
                "release_image_id",
                "dockerfile_path",
                "builder_identity",
                "build_workflow",
                "build_timestamp",
                "fdp39_provenance_artifact_ref",
                "fdp39_release_image_digest",
                "fixture_image_digest",
                "fixture_image_promotable",
                "ready_for_enablement_review",
                "production_enabled",
                "release_config_pr_required",
                "dual_control_required",
                "rollback_plan_ref",
                "operator_drill_ref",
                "security_review_ref"
        );
        assertSha256(manifest.get("release_image_digest"));
        assertSha256(manifest.get("release_image_id"));
        assertThat(manifest.get("release_image_digest")).isEqualTo(manifest.get("fdp39_release_image_digest"));
        assertThat(manifest.get("release_image_digest")).isNotEqualTo(manifest.get("fixture_image_digest"));
        assertThat(manifest.get("fixture_image_promotable")).isEqualTo("false");
        assertThat(manifest.get("production_enabled")).isEqualTo("false");
        assertThat(manifest.get("release_config_pr_required")).isEqualTo("true");
        assertThat(manifest.get("dual_control_required")).isEqualTo("true");
        assertThat(manifest.get("dockerfile_path")).isEqualTo("deployment/Dockerfile.backend");
        assertThat(manifest.get("dockerfile_path")).doesNotContain("Dockerfile.alert-service-fdp38-fixture");
        assertNoFallbackTokens(String.join("\n", manifest.values()));
    }

    static void assertAttestationValid(Map<String, Object> attestation, Map<String, String> manifest) {
        assertThat(attestation).containsKeys(
                "image_digest",
                "signature_subject",
                "certificate_identity",
                "certificate_issuer",
                "builder_identity",
                "source_repository",
                "commit_sha",
                "workflow_name",
                "workflow_run_id",
                "dockerfile_path",
                "build_type",
                "build_trigger",
                "provenance_predicate_type",
                "slsa_version_or_equivalent",
                "artifact_lineage_ref",
                "fdp39_provenance_ref"
        );
        String imageDigest = string(attestation, "image_digest");
        assertSha256(imageDigest);
        assertThat(imageDigest).isEqualTo(manifest.get("release_image_digest"));
        assertThat(imageDigest).isNotEqualTo(manifest.get("fixture_image_digest"));
        assertThat(string(attestation, "signature_subject")).endsWith(imageDigest);
        assertThat(string(attestation, "builder_identity")).isEqualTo(manifest.get("builder_identity"));
        assertThat(string(attestation, "source_repository")).isEqualTo("MiloszPodsiadly/fraud-detection-platform");
        assertThat(string(attestation, "dockerfile_path")).isEqualTo("deployment/Dockerfile.backend");
        assertNoFallbackTokens(attestation.toString());
    }

    static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean bool && bool;
    }

    static ProcessResult runGit(String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(Path.of("..").toAbsolutePath().normalize().toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(false, List.of(), "git command timed out: " + String.join(" ", args));
        }
        List<String> stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = reader.lines().filter(line -> !line.isBlank()).toList();
        }
        String stderr;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = String.join("\n", reader.lines().toList());
        }
        return new ProcessResult(process.exitValue() == 0, stdout, stderr);
    }

    private static List<String> command(String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=C:/Users/mpods/IdeaProjects/fraud-detection-platform");
        command.addAll(List.of(args));
        return command;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    record ProcessResult(boolean success, List<String> stdout, String stderr) {
    }
}
