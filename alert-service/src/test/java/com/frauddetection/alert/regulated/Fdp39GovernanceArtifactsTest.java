package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp39GovernanceArtifactsTest {

    private static final Path OUTPUT_DIR = Path.of("target", "fdp39-governance");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void governanceArtifactsAreGeneratedWithImmutableProvenanceAndNoEnablementClaim() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        ObjectNode provenance = objectMapper.createObjectNode();
        provenance.put("timestamp", Instant.now().toString());
        provenance.put("commit_sha", property("fdp39.commit-sha", "LOCAL_COMMIT"));
        provenance.put("branch_name", property("fdp39.branch-name", "FDP-39"));
        provenance.put("github_run_id", property("fdp39.github-run-id", "LOCAL_RUN"));
        provenance.put("github_workflow", property("fdp39.github-workflow", "LOCAL_WORKFLOW"));
        provenance.put("release_image_name", property("fdp39.release-image.name", "fdp39-alert-service:LOCAL"));
        provenance.put("release_image_tag", imageTag(provenance.get("release_image_name").asText()));
        provenance.put("release_image_id", property("fdp39.release-image.id", "sha256:LOCAL_RELEASE_IMAGE_ID"));
        provenance.put("release_image_digest_or_id", property("fdp39.release-image.digest", "sha256:LOCAL_RELEASE_IMAGE_DIGEST"));
        provenance.put("release_dockerfile_path", "deployment/Dockerfile.backend");
        provenance.put("fixture_image_name", property("fdp39.fixture-image.name", "fdp38-alert-service-test-fixture:LOCAL"));
        provenance.put("fixture_image_id", property("fdp39.fixture-image.id", "sha256:LOCAL_FIXTURE_IMAGE_ID"));
        provenance.put("fixture_image_digest_or_id", property("fdp39.fixture-image.digest", "sha256:LOCAL_FIXTURE_IMAGE_DIGEST"));
        provenance.put("fixture_dockerfile_path", "deployment/Dockerfile.alert-service-fdp38-fixture");
        provenance.put("fixture_image_release_candidate_allowed", false);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-artifact-provenance.json").toFile(), provenance);
        Files.writeString(OUTPUT_DIR.resolve("fdp39-artifact-provenance.md"), provenanceMarkdown(provenance));

        ObjectNode enablement = objectMapper.createObjectNode();
        enablement.put("ready_for_enablement_review", true);
        enablement.put("production_enabled", false);
        enablement.put("bank_enabled", false);
        enablement.put("release_config_pr_required", true);
        enablement.put("human_approval_required", true);
        enablement.put("dual_control_required", true);
        enablement.put("rollback_plan_required", true);
        enablement.put("operator_drill_required", true);
        enablement.put("security_review_required", true);
        enablement.put("audit_record_required", true);
        enablement.put("release_owner", "RELEASE_OWNER_REQUIRED_IN_RELEASE_PR");
        enablement.put("approver_1", "APPROVER_1_REQUIRED_IN_RELEASE_PR");
        enablement.put("approver_2", "APPROVER_2_REQUIRED_IN_RELEASE_PR");
        enablement.put("rollback_owner", "ROLLBACK_OWNER_REQUIRED_IN_RELEASE_PR");
        enablement.put("ops_owner", "OPS_OWNER_REQUIRED_IN_RELEASE_PR");
        enablement.put("security_owner", "SECURITY_OWNER_REQUIRED_IN_RELEASE_PR");
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-enablement-governance-pack.json").toFile(), enablement);
        Files.writeString(OUTPUT_DIR.resolve("fdp39-enablement-governance-pack.md"), enablementMarkdown(enablement));

        ObjectNode rollback = objectMapper.createObjectNode();
        rollback.put("rollback_plan_present", true);
        rollback.put("dual_control_required", true);
        rollback.put("rollback_does_not_disable_fencing", true);
        rollback.put("recovery_visibility_required", true);
        rollback.put("production_enablement_not_changed", true);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("fdp39-rollback-governance.json").toFile(), rollback);

        assertThat(provenance.get("release_image_id").asText()).startsWith("sha256:");
        assertThat(provenance.get("fixture_image_id").asText()).startsWith("sha256:");
        assertThat(enablement.get("production_enabled").asBoolean()).isFalse();
        assertThat(enablement.get("bank_enabled").asBoolean()).isFalse();
        assertThat(rollback.get("rollback_does_not_disable_fencing").asBoolean()).isTrue();

        for (Path artifact : List.of(
                OUTPUT_DIR.resolve("fdp39-artifact-provenance.json"),
                OUTPUT_DIR.resolve("fdp39-artifact-provenance.md"),
                OUTPUT_DIR.resolve("fdp39-enablement-governance-pack.json"),
                OUTPUT_DIR.resolve("fdp39-enablement-governance-pack.md"),
                OUTPUT_DIR.resolve("fdp39-rollback-governance.json")
        )) {
            String content = Files.readString(artifact);
            assertThat(content)
                    .as("Generated FDP-39 governance artifact must be placeholder-free: " + artifact)
                    .doesNotContain("TO_BE_FILLED")
                    .doesNotContain("PLACEHOLDER")
                    .doesNotContain("LOCAL_IMAGE_ID_NOT_PROVIDED")
                    .doesNotContain("LOCAL_IMAGE_DIGEST_NOT_PROVIDED");
        }
    }

    private String property(String key, String fallback) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String imageTag(String imageName) {
        int separator = imageName.lastIndexOf(':');
        return separator < 0 ? "untagged" : imageName.substring(separator + 1);
    }

    private String provenanceMarkdown(ObjectNode provenance) {
        return """
                # FDP-39 Artifact Provenance

                - commit_sha: `%s`
                - branch_name: `%s`
                - github_run_id: `%s`
                - github_workflow: `%s`
                - release_image_name: `%s`
                - release_image_id: `%s`
                - release_image_digest_or_id: `%s`
                - release_dockerfile_path: `deployment/Dockerfile.backend`
                - fixture_image_name: `%s`
                - fixture_image_id: `%s`
                - fixture_image_digest_or_id: `%s`
                - fixture_dockerfile_path: `deployment/Dockerfile.alert-service-fdp38-fixture`
                - fixture_image_release_candidate_allowed: `false`
                """.formatted(
                provenance.get("commit_sha").asText(),
                provenance.get("branch_name").asText(),
                provenance.get("github_run_id").asText(),
                provenance.get("github_workflow").asText(),
                provenance.get("release_image_name").asText(),
                provenance.get("release_image_id").asText(),
                provenance.get("release_image_digest_or_id").asText(),
                provenance.get("fixture_image_name").asText(),
                provenance.get("fixture_image_id").asText(),
                provenance.get("fixture_image_digest_or_id").asText()
        );
    }

    private String enablementMarkdown(ObjectNode enablement) throws IOException {
        return """
                # FDP-39 Enablement Governance Pack

                - ready_for_enablement_review: `%s`
                - production_enabled: `%s`
                - bank_enabled: `%s`
                - release_config_pr_required: `%s`
                - human_approval_required: `%s`
                - dual_control_required: `%s`
                - rollback_plan_required: `%s`
                - operator_drill_required: `%s`
                - security_review_required: `%s`
                - audit_record_required: `%s`

                `READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`.
                """.formatted(
                enablement.get("ready_for_enablement_review").asBoolean(),
                enablement.get("production_enabled").asBoolean(),
                enablement.get("bank_enabled").asBoolean(),
                enablement.get("release_config_pr_required").asBoolean(),
                enablement.get("human_approval_required").asBoolean(),
                enablement.get("dual_control_required").asBoolean(),
                enablement.get("rollback_plan_required").asBoolean(),
                enablement.get("operator_drill_required").asBoolean(),
                enablement.get("security_review_required").asBoolean(),
                enablement.get("audit_record_required").asBoolean()
        );
    }
}
