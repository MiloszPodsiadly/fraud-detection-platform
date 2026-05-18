package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionDoesNotStoreFullEvidenceSnapshotInFdp60Test {

    @Test
    void documentDoesNotContainEvidenceSnapshotItemList() {
        assertThat(List.of(SuspiciousTransactionDocument.class.getDeclaredFields()).stream().map(Field::getName))
                .doesNotContain("evidenceSnapshot");
    }
}
