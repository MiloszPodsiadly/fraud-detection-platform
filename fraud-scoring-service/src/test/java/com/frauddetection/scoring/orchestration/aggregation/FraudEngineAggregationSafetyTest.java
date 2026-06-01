package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineContribution;
import com.frauddetection.common.events.engine.FraudEngineContributionDirection;
import com.frauddetection.common.events.engine.FraudEngineEvidence;
import com.frauddetection.common.events.engine.FraudEngineEvidenceStatus;
import com.frauddetection.common.events.engine.FraudEngineEvidenceType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudEngineAggregationSafetyTest {

    @Test
    void nullIsOnlySafeAsOptionalText() throws Exception {
        assertThat(FraudEngineAggregationSafety.isSafe(null)).isTrue();

        String source = Files.readString(sourceFile("FraudEngineAggregationSafety.java"));
        assertThat(source).contains(
                "// Null means \"no optional text to scan\".",
                "// Required-field validation must happen before calling this helper.",
                "// Sanitizers must not use isSafe(null) as proof that required text is valid."
        );
    }

    @Test
    void sanitizersRejectNullRequiredFieldsEvenThoughSafetyHelperTreatsNullAsOptional() {
        FraudEngineEvidence evidence = mock(FraudEngineEvidence.class);
        when(evidence.evidenceType()).thenReturn(FraudEngineEvidenceType.MODEL_EXPLANATION);
        when(evidence.title()).thenReturn(null);
        when(evidence.description()).thenReturn("Safe description");
        when(evidence.source()).thenReturn("ML_MODEL");
        when(evidence.status()).thenReturn(FraudEngineEvidenceStatus.AVAILABLE);

        FraudEngineContribution contribution = mock(FraudEngineContribution.class);
        when(contribution.feature()).thenReturn(null);
        when(contribution.weight()).thenReturn(0.5d);
        when(contribution.direction()).thenReturn(FraudEngineContributionDirection.INCREASES_RISK);

        List<FraudEngineAggregationWarning> evidenceWarnings = new ArrayList<>();
        List<FraudEngineAggregationWarning> contributionWarnings = new ArrayList<>();

        assertThat(new FraudEngineEvidenceSanitizer().sanitize(
                "ml.python.primary",
                List.of(evidence),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                evidenceWarnings
        )).isEmpty();
        assertThat(new FraudEngineContributionSanitizer().sanitize(
                "rules.primary",
                List.of(contribution),
                FraudEngineAggregationPolicy.defaultInternalPolicy(),
                contributionWarnings
        )).isEmpty();
    }

    private Path sourceFile(String name) {
        Path moduleRelative = Path.of("src/main/java/com/frauddetection/scoring/orchestration/aggregation").resolve(name);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("fraud-scoring-service/src/main/java/com/frauddetection/scoring/orchestration/aggregation").resolve(name);
    }
}
