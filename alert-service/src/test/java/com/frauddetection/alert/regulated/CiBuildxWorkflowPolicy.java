package com.frauddetection.alert.regulated;

import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CiBuildxWorkflowPolicy {

    private CiBuildxWorkflowPolicy() {
    }

    static Map<String, Object> parse(String yaml) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> parsed = factory.getObject();
        return parsed == null ? Map.of() : parsed;
    }

    static Map<String, Object> job(Map<String, Object> workflow, String jobId) {
        Map<String, Object> jobs = map(workflow.get("jobs"));
        return map(jobs.get(jobId));
    }

    static List<Map<String, Object>> jobs(Map<String, Object> workflow) {
        return map(workflow.get("jobs")).entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Map<?, ?>)
                .map(entry -> map(entry.getValue()))
                .toList();
    }

    static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }

    static List<String> jobsUsingAction(Map<String, Object> workflow, String action) {
        Map<String, Object> workflowJobs = map(workflow.get("jobs"));
        return workflowJobs.entrySet().stream()
                .filter(entry -> steps(map(entry.getValue())).stream()
                        .anyMatch(step -> action.equals(string(step.get("uses")))))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    static List<Map<String, Object>> steps(Map<String, Object> job) {
        Object value = job.get("steps");
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Map.class::isInstance).map(CiBuildxWorkflowPolicy::map).toList();
    }

    static List<String> buildViolations(Map<String, Object> workflow,
                                        BuildExpectation expectation) {
        List<String> violations = new ArrayList<>();
        Map<String, Object> job = job(workflow, expectation.jobId());
        if (job.isEmpty()) {
            return List.of(expectation.jobId() + ": required CI job is missing");
        }
        List<Map<String, Object>> matching = steps(job).stream()
                .filter(step -> expectation.buildId().equals(string(step.get("id"))))
                .toList();
        if (matching.size() != 1) {
            violations.add(expectation.jobId() + ": expected exactly one Buildx step with id " + expectation.buildId());
            return violations;
        }
        Map<String, Object> build = matching.getFirst();
        String uses = string(build.get("uses"));
        if (!uses.matches("docker/build-push-action@[0-9a-f]{40}")) {
            violations.add(expectation.jobId() + ": image must be built by a commit-pinned docker/build-push-action");
        }
        Map<String, Object> with = map(build.get("with"));
        requireEqual(violations, expectation, with, "context", ".");
        requireEqual(violations, expectation, with, "file", expectation.dockerfile());
        requireEqual(violations, expectation, with, "tags", expectation.imageTag());
        if (!Boolean.FALSE.equals(with.get("push"))) {
            violations.add(expectation.jobId() + ": test image must not be pushed to a registry");
        }
        if (!Boolean.TRUE.equals(with.get("load"))) {
            violations.add(expectation.jobId() + ": Buildx must load the tested image into the local Docker Engine");
        }
        if (!Boolean.FALSE.equals(with.get("provenance"))) {
            violations.add(expectation.jobId() + ": local scanned image builds must disable attached provenance indexes so the loaded platform manifest identity remains verifiable");
        }
        if (!Boolean.TRUE.equals(with.get("pull"))) {
            violations.add(expectation.jobId() + ": Buildx must resolve the pinned Dockerfile inputs");
        }
        if (expectation.buildArg() != null && !expectation.buildArg().equals(string(with.get("build-args")))) {
            violations.add(expectation.jobId() + ": image build argument does not identify the expected application module");
        }
        if (expectation.requireScanOutput()) {
            String imageId = string(with.get("tags"));
            List<Map<String, Object>> scannerSteps = steps(job).stream()
                    .filter(step -> "./.github/actions/scan-built-image".equals(string(step.get("uses"))))
                    .filter(step -> imageId.equals(string(map(step.get("with")).get("image-ref"))))
                    .toList();
            if (scannerSteps.size() != 1) {
                violations.add(expectation.jobId() + ": built image must have exactly one identity-bound image scan");
            } else {
                Map<String, Object> scanWith = map(scannerSteps.getFirst().get("with"));
                requireEqual(violations, expectation, scanWith, "build-digest",
                        "${{ steps." + expectation.buildId() + ".outputs.digest }}");
                requireEqual(violations, expectation, scanWith, "build-metadata",
                        "${{ steps." + expectation.buildId() + ".outputs.metadata }}");
            }
        }
        return List.copyOf(violations);
    }

    static List<BuildUse> buildUses(Map<String, Object> workflow, String dockerfile) {
        Map<String, Object> workflowJobs = map(workflow.get("jobs"));
        List<BuildUse> uses = new ArrayList<>();
        for (Map.Entry<String, Object> jobEntry : workflowJobs.entrySet()) {
            Map<String, Object> job = map(jobEntry.getValue());
            for (Map<String, Object> step : steps(job)) {
                if (string(step.get("uses")).startsWith("docker/build-push-action@")
                        && dockerfile.equals(string(map(step.get("with")).get("file")))) {
                    uses.add(new BuildUse(jobEntry.getKey(), string(step.get("id")),
                            string(map(step.get("with")).get("tags"))));
                }
            }
        }
        return List.copyOf(uses);
    }

    static List<String> fixtureBuildViolations(Map<String, Object> workflow) {
        List<String> violations = new ArrayList<>();
        List<BuildUse> uses = buildUses(workflow, "deployment/Dockerfile.alert-service-fdp38-fixture");
        for (BuildUse use : uses) {
            if (!use.jobId().equals("fdp38-live-runtime-checkpoint-chaos")
                    && !use.jobId().equals("fdp39-release-governance")) {
                violations.add("Fixture Dockerfile is built by an unapproved CI job: " + use.jobId());
            }
        }
        if (uses.size() != 2) {
            violations.add("The fixture Dockerfile must have exactly one Buildx use in FDP-38 and one in FDP-39");
        }
        for (BuildExpectation expected : List.of(
                new BuildExpectation("fdp38-live-runtime-checkpoint-chaos", "build-fdp38-fixture",
                        "deployment/Dockerfile.alert-service-fdp38-fixture",
                        "fdp38-alert-service-test-fixture:${{ github.sha }}", null, false),
                new BuildExpectation("fdp39-release-governance", "build-fdp39-fixture-image",
                        "deployment/Dockerfile.alert-service-fdp38-fixture",
                        "fdp38-alert-service-test-fixture:${{ github.sha }}", null, true))) {
            long expectedUses = uses.stream()
                    .filter(use -> use.jobId().equals(expected.jobId()))
                    .filter(use -> use.buildId().equals(expected.buildId()))
                    .filter(use -> use.imageTag().equals(expected.imageTag()))
                    .count();
            if (expectedUses != 1) {
                violations.add(expected.jobId() + ": expected exactly one separately tagged fixture image build");
            }
            violations.addAll(buildViolations(workflow, expected));
        }
        return List.copyOf(violations);
    }

    private static void requireEqual(List<String> violations,
                                     BuildExpectation expectation,
                                     Map<String, Object> values,
                                     String key,
                                     String expected) {
        if (!expected.equals(string(values.get(key)))) {
            violations.add(expectation.jobId() + ": Buildx " + key + " must equal " + expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    record BuildExpectation(String jobId,
                            String buildId,
                            String dockerfile,
                            String imageTag,
                            String buildArg,
                            boolean requireScanOutput) {
    }

    record BuildUse(String jobId, String buildId, String imageTag) {
    }
}
