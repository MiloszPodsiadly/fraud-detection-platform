package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertEvidenceSnapshotPropertiesTest {

    @Test
    void defaultIsFifty() {
        assertThat(new AlertEvidenceSnapshotProperties(null).maxItems()).isEqualTo(50);
    }

    @Test
    void minTwoAndHardMaxAreAccepted() {
        assertThat(new AlertEvidenceSnapshotProperties(2).maxItems()).isEqualTo(2);
        assertThat(new AlertEvidenceSnapshotProperties(100).maxItems()).isEqualTo(100);
    }

    @Test
    void invalidBoundsFailFast() {
        assertThatThrownBy(() -> new AlertEvidenceSnapshotProperties(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertEvidenceSnapshotProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertEvidenceSnapshotProperties(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertEvidenceSnapshotProperties(101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
