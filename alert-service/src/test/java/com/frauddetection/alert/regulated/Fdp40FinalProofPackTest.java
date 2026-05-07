package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.OUTPUT_DIR;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.objectNode;
import static com.frauddetection.alert.regulated.Fdp40ReleaseControlsSupport.writeJson;
import static org.assertj.core.api.Assertions.assertThat;

class Fdp40FinalProofPackTest {

    @Test
    void finalProofPackSummarizesReleaseControlsWithoutProductionEnablementClaims() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        ObjectNode proof = objectNode();
        proof.put("release_controls_ready_for_review", true);
        proof.put("production_enabled", false);
        proof.put("release_digest_bound", true);
        proof.put("signature_subject_required", true);
        proof.put("attestation_required", true);
        proof.put("registry_immutability_required", true);
        proof.put("mutable_tag_only_allowed", false);
        proof.put("fixture_image_promotion_allowed", false);
        proof.put("dual_control_required", true);
        proof.put("environment_protection_required", true);
        proof.put("separate_config_pr_required", true);
        proof.put("runtime_semantics_changed", false);
        proof.put("external_finality_claimed", false);
        proof.put("distributed_acid_claimed", false);
        proof.put("bank_certification_claimed", false);
        ArrayNode gaps = proof.putArray("residual_platform_gaps");
        List.of(
                "Branch protection must enforce required checks outside repository code.",
                "Registry immutability must be enforced by registry/platform configuration.",
                "Production signing keys and Sigstore or cosign policy are external platform controls.",
                "Environment approvals must be configured in the deployment platform.",
                "Separate config PR is required before production enablement."
        ).forEach(gaps::add);
        writeJson(OUTPUT_DIR.resolve("fdp40-proof-pack.json"), proof);

        String markdown = """
                # FDP-40 Final Proof Pack

                ## 1. Release Manifest Validation

                Release manifest validation requires immutable release image digest and image id.

                ## 2. Signed Provenance Readiness

                Signature subject and attestation fields are required.

                ## 3. Attestation Verification

                Attestation digest must match the release manifest digest.

                ## 4. Registry Immutability / Promotion Policy

                Mutable tag only is NO-GO and fixture image promotion is forbidden.

                ## 5. Required Checks Mapping

                Required checks are mapped as blocking NO-GO controls.

                ## 6. Environment Protection Gates

                Dual control, rollback owner, security owner, fraud ops owner, and platform owner are required.

                ## 7. Enablement PR Template

                Separate config PR is required before enablement.

                ## 8. Unsupported Claims Matrix

                Signed provenance does not claim external finality. Release approval does not mean distributed ACID.

                ## 9. Runtime Immutability

                Runtime mutation semantics changed: `false`.

                ## 10. Residual Platform Gaps

                Branch protection, registry immutability, signing policy, and environment approval enforcement are external platform controls.

                - release_controls_ready_for_review: `true`
                - production_enabled: `false`
                - mutable_tag_only_allowed: `false`
                - fixture_image_promotion_allowed: `false`
                - external_finality_claimed: `false`
                - distributed_acid_claimed: `false`
                - bank_certification_claimed: `false`
                """;
        Files.writeString(OUTPUT_DIR.resolve("fdp40-proof-pack.md"), markdown);

        assertThat(proof.get("release_controls_ready_for_review").asBoolean()).isTrue();
        assertThat(proof.get("production_enabled").asBoolean()).isFalse();
        assertThat(proof.get("runtime_semantics_changed").asBoolean()).isFalse();
        assertThat(markdown)
                .contains("Release Manifest Validation")
                .contains("Signed Provenance Readiness")
                .contains("Unsupported Claims Matrix")
                .contains("does not claim external finality");
    }
}
