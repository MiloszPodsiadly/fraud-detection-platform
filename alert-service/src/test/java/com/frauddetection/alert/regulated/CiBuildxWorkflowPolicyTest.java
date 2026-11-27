package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CiBuildxWorkflowPolicyTest {

    private static final String BUILD_ID = "build-release-image";
    private static final String BUILD_TAG = "fdp39-alert-service:${{ github.sha }}";
    private static final String WORKFLOW_TEMPLATE = """
            jobs:
              fdp39-release-governance:
                steps:
                  - name: Build release image
                    id: build-release-image
                    uses: docker/build-push-action@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                    with:
                      context: .
                      file: %s
                      push: false
                      load: true
                      pull: true
                      provenance: false
                      tags: fdp39-alert-service:${{ github.sha }}
                      build-args: |
                        MODULE_NAME=alert-service
                  - name: Scan release image
                    uses: ./.github/actions/scan-built-image
                    with:
                      image-ref: fdp39-alert-service:${{ github.sha }}
                      build-digest: %s
                      build-metadata: %s
            """;

    @Test
    void rejectsWrongDockerfileForProductionImage() {
        Map<String, Object> workflow = releaseWorkflow("deployment/Dockerfile.unreviewed",
                "${{ steps." + BUILD_ID + ".outputs.digest }}",
                "${{ steps." + BUILD_ID + ".outputs.metadata }}");

        assertThat(violations(workflow))
                .anySatisfy(message -> assertThat(message).contains("Buildx file must equal deployment/Dockerfile.backend"));
    }

    @Test
    void rejectsFixtureDockerfileUsedAsReleaseImageEvenWhenFixtureBuildIsSeparatelyAllowed() {
        Map<String, Object> workflow = releaseWorkflow("deployment/Dockerfile.alert-service-fdp38-fixture",
                "${{ steps." + BUILD_ID + ".outputs.digest }}",
                "${{ steps." + BUILD_ID + ".outputs.metadata }}");

        assertThat(violations(workflow))
                .anySatisfy(message -> assertThat(message).contains("Buildx file must equal deployment/Dockerfile.backend"));
    }

    @Test
    void rejectsMissingBuildxOutputEvidence() {
        Map<String, Object> workflow = releaseWorkflow("deployment/Dockerfile.backend", "", "");

        assertThat(violations(workflow))
                .anySatisfy(message -> assertThat(message).contains("Buildx build-digest"))
                .anySatisfy(message -> assertThat(message).contains("Buildx build-metadata"));
    }

    @Test
    void rejectsProvenanceIndexForLocalScanImage() {
        String yaml = WORKFLOW_TEMPLATE.formatted("deployment/Dockerfile.backend",
                "${{ steps." + BUILD_ID + ".outputs.digest }}",
                "${{ steps." + BUILD_ID + ".outputs.metadata }}")
                .replace("                      provenance: false\n", "");
        Map<String, Object> workflow = CiBuildxWorkflowPolicy.parse(yaml);

        assertThat(violations(workflow))
                .anySatisfy(message -> assertThat(message).contains("disable attached provenance indexes"));
    }

    @Test
    void acceptsPinnedBuildxImageWithSeparateDigestAndMetadataEvidence() {
        Map<String, Object> workflow = releaseWorkflow("deployment/Dockerfile.backend",
                "${{ steps." + BUILD_ID + ".outputs.digest }}",
                "${{ steps." + BUILD_ID + ".outputs.metadata }}");

        assertThat(violations(workflow)).isEmpty();
    }

    private Map<String, Object> releaseWorkflow(String dockerfile, String digestOutput, String metadataOutput) {
        return CiBuildxWorkflowPolicy.parse(WORKFLOW_TEMPLATE.formatted(dockerfile, digestOutput, metadataOutput));
    }

    private java.util.List<String> violations(Map<String, Object> workflow) {
        return CiBuildxWorkflowPolicy.buildViolations(workflow,
                new CiBuildxWorkflowPolicy.BuildExpectation(
                        "fdp39-release-governance",
                        BUILD_ID,
                        "deployment/Dockerfile.backend",
                        BUILD_TAG,
                        "MODULE_NAME=alert-service",
                        true
                ));
    }
}
