package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp45FraudCaseWorkQueueCursorQueryFingerprintTest {

    @Test
    void shouldProduceStableHashForNormalizedEquivalentQueryShape() {
        Sort.Order sort = Sort.Order.desc("createdAt");

        String first = fingerprint(FraudCaseStatus.OPEN, " analyst-1 ", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-1", sort);
        String second = fingerprint(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-1", sort);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void shouldTreatNullAndAbsentFiltersConsistently() {
        Sort.Order sort = Sort.Order.desc("createdAt");

        assertThat(fingerprint(null, null, null, null, null, null, null, null, null, sort))
                .isEqualTo(fingerprint(null, " ", null, null, null, null, null, null, " ", sort));
    }

    @Test
    void shouldChangeWhenFilterOrSortShapeChanges() {
        Sort.Order sort = Sort.Order.desc("createdAt");
        String baseline = fingerprint(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-1", sort);

        assertThat(fingerprint(FraudCaseStatus.CLOSED, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-1", sort)).isNotEqualTo(baseline);
        assertThat(fingerprint(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-02T00:00:00Z"), null, null, null, "alert-1", sort)).isNotEqualTo(baseline);
        assertThat(fingerprint(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-2", sort)).isNotEqualTo(baseline);
        assertThat(fingerprint(FraudCaseStatus.OPEN, "analyst-1", FraudCasePriority.HIGH, RiskLevel.HIGH,
                Instant.parse("2026-05-01T00:00:00Z"), null, null, null, "alert-1", Sort.Order.asc("createdAt"))).isNotEqualTo(baseline);
    }

    @Test
    void shouldNotDependOnPageSizeOrCursorParameterBecauseTheyAreNotInputs() {
        Sort.Order sort = Sort.Order.desc("createdAt");

        String hash = fingerprint(FraudCaseStatus.OPEN, null, null, null, null, null, null, null, null, sort);

        assertThat(hash).isEqualTo(fingerprint(FraudCaseStatus.OPEN, null, null, null, null, null, null, null, null, sort));
    }

    private String fingerprint(
            FraudCaseStatus status,
            String assignee,
            FraudCasePriority priority,
            RiskLevel riskLevel,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            String linkedAlertId,
            Sort.Order sort
    ) {
        return FraudCaseWorkQueueCursorQueryFingerprint.hash(
                status,
                assignee,
                priority,
                riskLevel,
                createdFrom,
                createdTo,
                updatedFrom,
                updatedTo,
                linkedAlertId,
                sort,
                FraudCaseWorkQueueCursorCodec.VERSION
        );
    }
}
