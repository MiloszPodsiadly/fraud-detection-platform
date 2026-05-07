package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.OUTPUT_DIR;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.bool;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.objectNode;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.readJson;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.string;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.writeJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fdp40RegistryPromotionPolicyTest {

    @Test
    void registryPromotionPolicyRequiresImmutableSignedDigest() throws Exception {
        assertThat(Files.readString(Path.of("../docs/release/FDP-40-registry-promotion-policy.md")))
                .contains("Mutable tag alone is NO-GO")
                .contains("NOT_READY_FOR_PRODUCTION_ENABLEMENT");
        Map<String, Object> policy = readJson(Path.of("../docs/release/FDP-40-registry-promotion-policy.json"));

        assertPromotionPolicyValid(policy);
        ObjectNode artifact = objectNode();
        artifact.put("promotion_policy_valid", true);
        artifact.put("release_digest_bound", true);
        artifact.put("mutable_tag_only_allowed", false);
        artifact.put("fixture_image_promotion_allowed", false);
        artifact.put("registry_immutability_required", true);
        artifact.put("registry_immutability_verified_by_fdp40", false);
        artifact.put("registry_immutability_enforced_by_fdp40", false);
        artifact.put("external_registry_control_required", true);
        artifact.put("production_readiness", string(policy, "production_readiness"));
        writeJson(OUTPUT_DIR.resolve("fdp40-registry-promotion-policy.json"), artifact);
    }

    @Test
    void invalidRegistryPromotionCasesFailClosed() throws Exception {
        Map<String, Object> valid = readJson(Path.of("../docs/release/FDP-40-registry-promotion-policy.json"));

        assertInvalid(valid, policy -> policy.put("promotion_digest", ""));
        assertInvalid(valid, policy -> policy.put("mutable_tag_only_allowed", true));
        assertInvalid(valid, policy -> policy.put("fixture_image_promotion_allowed", true));
        assertInvalid(valid, policy -> policy.put("fdp39_release_image_digest", "sha256:999"));
        assertInvalid(valid, policy -> policy.put("signed_provenance_readiness", false));
        assertInvalid(valid, policy -> policy.put("registry_immutability_required", false));
        assertInvalid(valid, policy -> policy.put("registry_immutability_verified_by_fdp40", true));
        assertInvalid(valid, policy -> policy.put("registry_immutability_enforced_by_fdp40", true));
        assertInvalid(valid, policy -> policy.put("external_registry_control_required", false));
    }

    private void assertPromotionPolicyValid(Map<String, Object> policy) {
        assertThat(string(policy, "promotion_digest")).startsWith("sha256:");
        assertThat(string(policy, "promotion_digest")).isEqualTo(string(policy, "release_image_digest"));
        assertThat(string(policy, "promotion_digest")).isEqualTo(string(policy, "fdp39_release_image_digest"));
        assertThat(string(policy, "promotion_digest")).isEqualTo(string(policy, "attested_release_image_digest"));
        assertThat(string(policy, "rollback_digest")).startsWith("sha256:");
        assertThat(bool(policy, "mutable_tag_only_allowed")).isFalse();
        assertThat(bool(policy, "fixture_image_promotion_allowed")).isFalse();
        assertThat(bool(policy, "signed_provenance_readiness")).isTrue();
        assertThat(bool(policy, "registry_immutability_required")).isTrue();
        assertThat(bool(policy, "registry_immutability_verified_by_fdp40")).isFalse();
        assertThat(bool(policy, "registry_immutability_enforced_by_fdp40")).isFalse();
        assertThat(bool(policy, "external_registry_control_required")).isTrue();
        assertThat(bool(policy, "release_tag_non_overwritable_required")).isTrue();
        assertThat(string(policy, "registry_repository")).isNotBlank();
        assertThat(string(policy, "promotion_timestamp")).isNotBlank();
    }

    private void assertInvalid(Map<String, Object> valid, java.util.function.Consumer<Map<String, Object>> mutation) {
        Map<String, Object> candidate = new LinkedHashMap<>(valid);
        mutation.accept(candidate);
        assertThatThrownBy(() -> assertPromotionPolicyValid(candidate))
                .isInstanceOf(AssertionError.class);
    }
}
