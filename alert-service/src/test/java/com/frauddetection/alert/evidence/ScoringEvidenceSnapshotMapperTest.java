package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringEvidenceSnapshotMapperTest {

    private final ScoringEvidenceSnapshotMapper mapper = new ScoringEvidenceSnapshotMapper();

    @Test
    void everyScoringEvidenceTypeMapsExplicitly() {
        for (ScoringEvidenceType type : ScoringEvidenceType.values()) {
            assertThat(mapper.mapType(type)).isNotNull();
        }
    }

    @Test
    void everyScoringEvidenceStatusMapsExplicitly() {
        for (ScoringEvidenceStatus status : ScoringEvidenceStatus.values()) {
            assertThat(mapper.mapStatus(status)).isNotNull();
        }
    }

    @Test
    void everyScoringEvidenceSeverityMapsExplicitly() {
        for (ScoringEvidenceSeverity severity : ScoringEvidenceSeverity.values()) {
            assertThat(mapper.mapSeverity(severity)).isNotNull();
        }
    }

    @Test
    void everyScoringEvidenceSourceMapsExplicitly() {
        for (ScoringEvidenceSource source : ScoringEvidenceSource.values()) {
            assertThat(mapper.mapSource(source)).isNotNull();
        }
    }

    @Test
    void diagnosticStatusNeverMapsToAvailable() {
        assertThat(mapper.mapStatus(ScoringEvidenceStatus.PARTIAL)).isNotEqualTo(EvidenceStatus.AVAILABLE);
    }

    @Test
    void nullInputsThrow() {
        assertThatThrownBy(() -> mapper.mapType(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.mapStatus(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.mapSeverity(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.mapSource(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
