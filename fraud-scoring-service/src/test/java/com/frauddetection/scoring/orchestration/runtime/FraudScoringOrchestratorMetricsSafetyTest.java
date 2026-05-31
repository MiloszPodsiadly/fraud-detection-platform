package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudSignalEngineRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudScoringOrchestratorMetricsSafetyTest {

    private final NoOpFraudScoringOrchestratorMetrics metrics = new NoOpFraudScoringOrchestratorMetrics();

    @Test
    void metricsUseOnlyAllowlistedEngineIds() {
        metrics.recordEngineResult("rules.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, true);
        metrics.recordEngineResult("ml.python.primary", FraudEngineType.ML_MODEL, FraudEngineStatus.TIMEOUT, false);

        assertThatThrownBy(() -> metrics.recordEngineResult(
                "unknown.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("METRICS_UNKNOWN_ENGINE_ID");
    }

    @Test
    void metricsUseOnlyBoundedStatuses() {
        metrics.recordEngineResult("rules.primary", FraudEngineType.RULES, FraudEngineStatus.DEGRADED, true);

        assertThatThrownBy(() -> metrics.recordEngineResult(
                "rules.primary", FraudEngineType.RULES, FraudEngineStatus.FALLBACK_USED, true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("METRICS_UNKNOWN_ENGINE_STATUS");
    }

    @Test
    void metricsRejectOrDoNotExposeTransactionIdentifiers() throws Exception {
        assertMetricSourcesDoNotContain("transactionid");
    }

    @Test
    void metricsRejectOrDoNotExposeCustomerIdentifiers() throws Exception {
        assertMetricSourcesDoNotContain("customerid", "accountid", "cardid", "merchantid");
    }

    @Test
    void metricsRejectOrDoNotExposeRawExceptionText() throws Exception {
        assertMetricSourcesDoNotContain("exception.getmessage", "raw reason", "raw feature");
    }

    @Test
    void metricsRejectOrDoNotExposePayloadEndpointOrToken() throws Exception {
        assertMetricSourcesDoNotContain("payload", "endpoint", "token", "secret");
    }

    @Test
    void timeoutMetricsAreLowCardinality() {
        metrics.recordTimeout(
                FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineType.ML_MODEL,
                false
        );
        metrics.recordEngineLatency(
                FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.TIMEOUT,
                false,
                Duration.ofMillis(100)
        );
    }

    @Test
    void requiredEngineFailedMetricsAreLowCardinality() {
        metrics.recordRequiredEngineFailed(FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID);

        assertThatThrownBy(() -> metrics.recordRequiredEngineFailed("unknown.primary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("METRICS_UNKNOWN_REQUIRED_ENGINE_ID");
    }

    @Test
    void metricsStatusAllowlistMustTrackFraudEngineStatusVocabulary() {
        Set<FraudEngineStatus> reviewedVocabulary = EnumSet.of(
                FraudEngineStatus.AVAILABLE,
                FraudEngineStatus.UNAVAILABLE,
                FraudEngineStatus.DEGRADED,
                FraudEngineStatus.TIMEOUT,
                FraudEngineStatus.FALLBACK_USED,
                FraudEngineStatus.SKIPPED
        );
        Set<FraudEngineStatus> allowlistedStatuses = EnumSet.noneOf(FraudEngineStatus.class);
        for (FraudEngineStatus status : FraudEngineStatus.values()) {
            if (isMetricsStatusAllowlisted(status)) {
                allowlistedStatuses.add(status);
            }
        }

        assertThat(EnumSet.allOf(FraudEngineStatus.class))
                .withFailMessage("METRICS_STATUS_ALLOWLIST_REVIEW_REQUIRED")
                .isEqualTo(reviewedVocabulary);
        assertThat(allowlistedStatuses)
                .withFailMessage("METRICS_STATUS_ALLOWLIST_REVIEW_REQUIRED")
                .containsExactlyInAnyOrder(
                        FraudEngineStatus.AVAILABLE,
                        FraudEngineStatus.UNAVAILABLE,
                        FraudEngineStatus.TIMEOUT,
                        FraudEngineStatus.DEGRADED
                );
    }

    @Test
    void requiredEngineFailedMetricAllowsOnlyRulesPrimaryForFdp90() {
        metrics.recordRequiredEngineFailed(FraudSignalEngineRegistry.RULES_PRIMARY_ENGINE_ID);

        assertThatThrownBy(() -> metrics.recordRequiredEngineFailed(FraudSignalEngineRegistry.PYTHON_ML_PRIMARY_ENGINE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("METRICS_UNKNOWN_REQUIRED_ENGINE_ID");
    }

    @Test
    void docsExplainMlRequiredToRegisterButNotRequiredToSucceed() throws Exception {
        String docs = Files.readString(docsRoot().resolve("architecture/orchestrator_runtime_readiness.md"))
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(docs)
                .contains("ml.python.primary is required-to-register")
                .contains("ml.python.primary is required-to-register but optional-to-succeed")
                .contains("ml runtime timeout/unavailable/rejection is partial")
                .contains("missing ml registration is construction failure");
    }

    private void assertMetricSourcesDoNotContain(String... forbiddenValues) throws Exception {
        Path runtimeRoot = Path.of("src/main/java/com/frauddetection/scoring/orchestration/runtime");
        StringBuilder source = new StringBuilder();
        try (Stream<Path> files = Files.walk(runtimeRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().contains("Metric"))
                    .toList()) {
                source.append(Files.readString(file)).append('\n');
            }
        }
        assertThat(source.toString().toLowerCase(Locale.ROOT)).doesNotContain(forbiddenValues);
    }

    private boolean isMetricsStatusAllowlisted(FraudEngineStatus status) {
        try {
            FraudScoringOrchestratorMetricLabels.validateStatus(status);
            return true;
        } catch (IllegalArgumentException exception) {
            assertThat(exception).hasMessage("METRICS_UNKNOWN_ENGINE_STATUS");
            return false;
        }
    }

    private Path docsRoot() {
        Path moduleRelative = Path.of("..", "docs");
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("docs");
    }
}
