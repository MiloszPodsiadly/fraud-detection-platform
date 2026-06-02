package com.frauddetection.alert.engineintelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceProjectionArchitectureGuardTest {

    private static final List<String> FORBIDDEN_RAW_STORAGE = List.of(
            "rawevidence", "rawcontribution", "featuresnapshot", "featurevector", "rawpayload",
            "endpoint", "token", "secret", "stacktrace", "exceptionmessage", "internalaggregation",
            "fraudengineaggregationresult", "normalizedfraudengineresult", "scoringcontext", "rawmlresponse"
    );
    private static final List<String> FORBIDDEN_DECISION_STORAGE = List.of(
            "alertseverity", "alertpriority", "fraudcasestatus", "approve", "decline", "block",
            "paymentauthorization", "recommendedaction", "finaldecision"
    );

    @Test
    void engineIntelligenceProjectionDoesNotStoreRawOrInternalData() throws Exception {
        String declaredFields = Stream.of(
                        EngineIntelligenceProjection.class,
                        EngineIntelligenceEngineProjection.class,
                        EngineIntelligenceDiagnosticSignalProjection.class,
                        EngineIntelligenceWarningProjection.class
                )
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getName)
                .map(this::compact)
                .reduce("", String::concat);
        String serialized = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(new EngineIntelligenceProjectionMapper(new EngineIntelligenceProjectionPolicy())
                        .map("txn-guard", EngineIntelligenceProjectionTestFixtures.fullSummary(), null)
                        .projection()
                        .orElseThrow());

        assertThat(declaredFields).doesNotContain(FORBIDDEN_RAW_STORAGE.toArray(String[]::new));
        assertThat(compact(serialized)).doesNotContain(FORBIDDEN_RAW_STORAGE.toArray(String[]::new));
        assertThat(declaredFields).doesNotContain(FORBIDDEN_DECISION_STORAGE.toArray(String[]::new));
        assertThat(compact(serialized)).doesNotContain(FORBIDDEN_DECISION_STORAGE.toArray(String[]::new));
    }

    @Test
    void decisioningCodeDoesNotImportEngineIntelligenceProjection() throws Exception {
        String decisioningSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/fraudcase",
                "alert-service/src/main/java/com/frauddetection/alert/regulated",
                "alert-service/src/main/java/com/frauddetection/alert/suspicious",
                "alert-service/src/main/java/com/frauddetection/alert/service/AlertManagementService.java"
        );

        assertThat(decisioningSources).doesNotContain(
                "EngineIntelligenceProjection",
                "EngineIntelligenceProjectionRepository",
                "EngineIntelligenceProjectionService",
                "EngineIntelligenceSummary"
        );
    }

    @Test
    void projectionMapperDoesNotUseStringMessageClassification() throws Exception {
        String validationSources = sources(
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/EngineIntelligenceProjectionMapper.java",
                "alert-service/src/main/java/com/frauddetection/alert/engineintelligence/EngineIntelligenceProjectionPolicy.java"
        );

        assertThat(validationSources).doesNotContain(
                "classify(",
                "getMessage()",
                "exception.getMessage()",
                "contains(\"ENGINE_INTELLIGENCE"
        );
    }

    private String sources(String... relativePaths) throws IOException {
        StringBuilder sources = new StringBuilder();
        for (String relativePath : relativePaths) {
            Path path = repositoryRoot().resolve(relativePath);
            if (Files.isRegularFile(path)) {
                sources.append(Files.readString(path));
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        sources.append(Files.readString(file));
                    }
                }
            }
        }
        return sources.toString();
    }

    private Path repositoryRoot() {
        Path current = Path.of(".").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("alert-service")) && Files.isDirectory(candidate.resolve("common-events"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("REPOSITORY_ROOT_MISSING");
    }

    private String compact(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
