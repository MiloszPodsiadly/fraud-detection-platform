package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readYamlKeyValues;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.string;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40ReleaseEvidenceVerificationNegativeTest {

    @Test
    void malformedReleaseEvidenceFailsClosed() throws Exception {
        Map<String, String> manifest = readYamlKeyValues(java.nio.file.Path.of("../docs/release/fdp-40-release-manifest-template.yaml"));
        Map<String, Object> attestation = readJson(java.nio.file.Path.of("../docs/release/fdp-40-attestation-readiness-template.json"));
        Map<String, Object> fdp39 = readJson(java.nio.file.Path.of("../docs/release/fdp-40-fdp39-provenance-reference.json"));
        Map<String, Object> checks = readJson(java.nio.file.Path.of("../docs/release/fdp-40-required-checks-matrix.json"));

        List.of(
                invalid(manifest, attestation, fdp39, checks, m -> m.remove("release_image_digest")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_image_digest", "alert-service:latest")),
                invalid(manifest, attestation, fdp39, checks, (m, a) -> a.put("image_digest", "sha256:999")),
                invalidFdp39(manifest, attestation, fdp39, checks, p -> p.put("release_image_digest_or_id", "sha256:999")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("fixture_image_digest", m.get("release_image_digest"))),
                invalidChecks(manifest, attestation, fdp39, checks, c -> ((Map<String, Object>) ((List<?>) c.get("checks")).get(0)).put("blocking", false)),
                invalidChecks(manifest, attestation, fdp39, checks, c -> ((List<?>) c.get("checks")).remove(0)),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("production_enabled", "true")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("bank_enabled", "true")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_config_pr_required", "false")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("dual_control_required", "true")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("single_release_owner_model", "false")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_owner_required", "false")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_owner_must_be_named", "false")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("signed_provenance_readiness", "false")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("signing_enforced_by_fdp40", "true")),
                invalid(manifest, attestation, fdp39, checks, m -> m.remove("release_image_digest")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_image_digest", "LOCAL_IMAGE")),
                invalid(manifest, attestation, fdp39, checks, m -> m.put("release_image_digest", "TO_BE_FILLED")),
                invalid(manifest, attestation, fdp39, checks, m -> m.remove("builder_identity")),
                invalid(manifest, attestation, fdp39, checks, m -> m.remove("github_run_id")),
                invalid(manifest, attestation, fdp39, checks, m -> m.remove("dockerfile_path"))
        ).forEach(result -> assertThat(result.failureReasons()).isNotEmpty());
    }

    private VerificationResult invalid(
            Map<String, String> manifest,
            Map<String, Object> attestation,
            Map<String, Object> fdp39,
            Map<String, Object> checks,
            java.util.function.Consumer<Map<String, String>> mutation
    ) {
        Map<String, String> candidateManifest = new LinkedHashMap<>(manifest);
        mutation.accept(candidateManifest);
        return verify(candidateManifest, new LinkedHashMap<>(attestation), new LinkedHashMap<>(fdp39), cloneChecks(checks));
    }

    private VerificationResult invalid(
            Map<String, String> manifest,
            Map<String, Object> attestation,
            Map<String, Object> fdp39,
            Map<String, Object> checks,
            java.util.function.BiConsumer<Map<String, String>, Map<String, Object>> mutation
    ) {
        Map<String, String> candidateManifest = new LinkedHashMap<>(manifest);
        Map<String, Object> candidateAttestation = new LinkedHashMap<>(attestation);
        mutation.accept(candidateManifest, candidateAttestation);
        return verify(candidateManifest, candidateAttestation, new LinkedHashMap<>(fdp39), cloneChecks(checks));
    }

    private VerificationResult invalidFdp39(
            Map<String, String> manifest,
            Map<String, Object> attestation,
            Map<String, Object> fdp39,
            Map<String, Object> checks,
            java.util.function.Consumer<Map<String, Object>> mutation
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>(fdp39);
        mutation.accept(candidate);
        return verify(new LinkedHashMap<>(manifest), new LinkedHashMap<>(attestation), candidate, cloneChecks(checks));
    }

    private VerificationResult invalidChecks(
            Map<String, String> manifest,
            Map<String, Object> attestation,
            Map<String, Object> fdp39,
            Map<String, Object> checks,
            java.util.function.Consumer<Map<String, Object>> mutation
    ) {
        Map<String, Object> candidate = cloneChecks(checks);
        mutation.accept(candidate);
        return verify(new LinkedHashMap<>(manifest), new LinkedHashMap<>(attestation), new LinkedHashMap<>(fdp39), candidate);
    }

    private VerificationResult verify(
            Map<String, String> manifest,
            Map<String, Object> attestation,
            Map<String, Object> fdp39,
            Map<String, Object> checks
    ) {
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        boolean manifestValid = manifest.getOrDefault("release_image_digest", "").startsWith("sha256:")
                && manifest.getOrDefault("release_image_id", "").startsWith("sha256:")
                && !manifest.getOrDefault("builder_identity", "").isBlank()
                && !manifest.getOrDefault("github_run_id", "").isBlank()
                && !manifest.getOrDefault("dockerfile_path", "").isBlank()
                && !String.join("\n", manifest.values()).contains("LOCAL_")
                && !String.join("\n", manifest.values()).contains("TO_BE_FILLED");
        boolean attestationDigestMatch = manifest.get("release_image_digest") != null
                && manifest.get("release_image_digest").equals(string(attestation, "image_digest"));
        boolean fdp39DigestMatch = manifest.get("release_image_digest") != null
                && manifest.get("release_image_digest").equals(string(fdp39, "release_image_digest_or_id"));
        boolean fixtureNotPromoted = manifest.get("release_image_digest") != null
                && !manifest.get("release_image_digest").equals(manifest.get("fixture_image_digest"))
                && !bool(fdp39, "fixture_image_release_candidate_allowed");
        List<?> rawChecks = (List<?>) checks.get("checks");
        boolean requiredChecksPresent = rawChecks.size() == 9 && rawChecks.stream()
                .map(Map.class::cast)
                .allMatch(check -> Boolean.TRUE.equals(check.get("required")) && Boolean.TRUE.equals(check.get("blocking")));
        if (!manifestValid) failures.add("manifest_invalid");
        if (!attestationDigestMatch) failures.add("attestation_digest_mismatch");
        if (!fdp39DigestMatch) failures.add("fdp39_digest_mismatch");
        if (!fixtureNotPromoted) failures.add("fixture_promoted");
        if (!requiredChecksPresent) failures.add("required_checks_missing");
        if (!"false".equals(manifest.get("production_enabled"))) failures.add("production_enabled_true");
        if (!"false".equals(manifest.get("bank_enabled"))) failures.add("bank_enabled_true");
        if (!"true".equals(manifest.get("release_config_pr_required"))) failures.add("release_config_pr_not_required");
        if (!"true".equals(manifest.get("single_release_owner_model"))) failures.add("single_release_owner_model_missing");
        if (!"true".equals(manifest.get("release_owner_required"))) failures.add("release_owner_not_required");
        if (!"true".equals(manifest.get("release_owner_must_be_named"))) failures.add("release_owner_name_not_required");
        if (!"true".equals(manifest.get("signed_provenance_readiness"))) failures.add("signing_readiness_missing");
        if (!"false".equals(manifest.get("signing_enforced_by_fdp40"))) failures.add("signing_enforcement_unexpected");
        if (!"false".equals(manifest.get("dual_control_required"))) failures.add("dual_control_must_be_false");
        return new VerificationResult(failures);
    }

    private Map<String, Object> cloneChecks(Map<String, Object> checks) {
        Map<String, Object> clone = new LinkedHashMap<>();
        List<Map<String, Object>> copiedChecks = ((List<?>) checks.get("checks")).stream()
                .map(Map.class::cast)
                .map(LinkedHashMap::new)
                .map(map -> (Map<String, Object>) map)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        clone.put("checks", copiedChecks);
        return clone;
    }

    private record VerificationResult(List<String> failureReasons) {
    }
}
