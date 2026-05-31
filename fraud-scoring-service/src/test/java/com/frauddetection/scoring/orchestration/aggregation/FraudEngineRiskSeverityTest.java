package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineRiskSeverityTest {

    @Test
    void explicitRiskSeverityOrderingIsStable() {
        assertThat(FraudEngineRiskSeverity.rank(RiskLevel.LOW))
                .isLessThan(FraudEngineRiskSeverity.rank(RiskLevel.MEDIUM));
        assertThat(FraudEngineRiskSeverity.rank(RiskLevel.MEDIUM))
                .isLessThan(FraudEngineRiskSeverity.rank(RiskLevel.HIGH));
        assertThat(FraudEngineRiskSeverity.rank(RiskLevel.HIGH))
                .isLessThan(FraudEngineRiskSeverity.rank(RiskLevel.CRITICAL));
        assertThat(FraudEngineRiskSeverity.rank(null)).isEqualTo(-1);
    }

    @Test
    void distanceUsesExplicitRanks() {
        assertThat(FraudEngineRiskSeverity.distance(RiskLevel.LOW, RiskLevel.HIGH)).isEqualTo(2);
        assertThat(FraudEngineRiskSeverity.distance(RiskLevel.HIGH, RiskLevel.CRITICAL)).isEqualTo(1);
    }

    @Test
    void aggregationPackageDoesNotUseRiskLevelOrdinal() throws Exception {
        Path root = Path.of("src/main/java/com/frauddetection/scoring/orchestration/aggregation");
        try (Stream<Path> files = Files.walk(root)) {
            String source = files
                    .filter(Files::isRegularFile)
                    .map(this::read)
                    .reduce("", String::concat);

            assertThat(source).doesNotContain(".ordinal()");
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read " + path, exception);
        }
    }
}
