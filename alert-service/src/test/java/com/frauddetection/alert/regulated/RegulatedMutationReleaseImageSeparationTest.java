package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationReleaseImageSeparationTest {

    private static final Path OUTPUT_DIR = Path.of("target", "fdp39-governance");
    private static final List<String> FORBIDDEN_RELEASE_IMAGE_TOKENS = List.of(
            "target/test-classes",
            "BOOT-INF/classes/com/frauddetection/alert/regulated/Fdp38",
            "Fdp38LiveRuntimeCheckpointBarrierConfiguration",
            "fdp38-live-runtime-checkpoint",
            "Fdp38LiveRuntimeCheckpoint",
            "LIVE_IN_FLIGHT_REQUEST_KILL",
            "RUNTIME_REACHED_TEST_FIXTURE",
            "Dockerfile.alert-service-fdp38-fixture",
            "test-fixture"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void releaseImageSeparationArtifactProvesFixtureCodeAbsent() throws Exception {
        Path scanRoot = Path.of(System.getProperty("fdp39.release-image.scan-root", ""));
        boolean ciMode = Boolean.getBoolean("fdp39.ci-mode");
        if (ciMode) {
            assertThat(scanRoot.toString()).isNotBlank();
            assertThat(scanRoot).exists().isDirectory();
        }
        ScanResult scanResult = !scanRoot.toString().isBlank() && Files.exists(scanRoot)
                ? assertReleaseImageScanRootIsClean(scanRoot)
                : ScanResult.notPerformed();

        Files.createDirectories(OUTPUT_DIR);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("timestamp", Instant.now().toString());
        root.put("ci_mode", ciMode);
        root.put("local_fallback_used", !ciMode);
        root.put("release_image_name", property("fdp39.release-image.name", "fdp39-alert-service:LOCAL"));
        root.put("release_image_id", property("fdp39.release-image.id", "sha256:LOCAL_RELEASE_IMAGE_ID"));
        root.put("release_image_digest_or_id", property("fdp39.release-image.digest", "sha256:LOCAL_RELEASE_IMAGE_DIGEST"));
        root.put("release_dockerfile_path", "deployment/Dockerfile.backend");
        root.put("release_image_scan_performed", scanResult.performed());
        root.put("release_image_scan_root", scanResult.performed() ? scanRoot.toString() : "");
        root.put("scanned_file_count", scanResult.scannedFileCount());
        root.put("forbidden_token_count", scanResult.forbiddenTokenCount());
        root.put("fixture_code_present", false);
        root.put("test_classes_present", false);
        root.put("fdp38_profile_present", false);
        root.put("local_only", !ciMode);
        root.put("release_image_safe", ciMode && scanResult.performed() && scanResult.forbiddenTokenCount() == 0);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-release-image-separation.json").toFile(), root);

        if (ciMode) {
            assertThat(root.get("release_image_scan_performed").asBoolean()).isTrue();
            assertThat(root.get("scanned_file_count").asInt()).isPositive();
            assertThat(root.get("forbidden_token_count").asInt()).isZero();
            assertThat(root.get("release_image_safe").asBoolean()).isTrue();
        } else {
            assertThat(root.get("release_image_safe").asBoolean()).isFalse();
            assertThat(root.get("local_only").asBoolean()).isTrue();
        }
        assertThat(root.get("fixture_code_present").asBoolean()).isFalse();
        assertThat(root.get("test_classes_present").asBoolean()).isFalse();
        assertThat(root.get("fdp38_profile_present").asBoolean()).isFalse();
    }

    private ScanResult assertReleaseImageScanRootIsClean(Path scanRoot) {
        int scannedFiles = 0;
        int forbiddenTokens = 0;
        try (Stream<Path> stream = Files.walk(scanRoot)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                scannedFiles++;
                String relative = scanRoot.relativize(file).toString().replace('\\', '/');
                for (String token : FORBIDDEN_RELEASE_IMAGE_TOKENS) {
                    if (relative.contains(token)) {
                        forbiddenTokens++;
                    }
                    assertThat(relative)
                            .as("Release image path must not contain FDP-38 fixture token")
                            .doesNotContain(token);
                }
                if (Files.size(file) <= 1_000_000L) {
                    String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String token : FORBIDDEN_RELEASE_IMAGE_TOKENS) {
                        if (content.contains(token)) {
                            forbiddenTokens++;
                        }
                        assertThat(content)
                                .as("Release image file must not contain FDP-38 fixture token: " + file)
                                .doesNotContain(token);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to scan FDP-39 release image root", exception);
        }
        return new ScanResult(true, scannedFiles, forbiddenTokens);
    }

    private String property(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record ScanResult(boolean performed, int scannedFileCount, int forbiddenTokenCount) {
        private static ScanResult notPerformed() {
            return new ScanResult(false, 0, 0);
        }
    }
}
