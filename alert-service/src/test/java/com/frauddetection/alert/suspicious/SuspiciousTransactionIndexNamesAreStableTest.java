package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.EXPECTED_INDEX_NAMES;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIndexNamesAreStableTest {

    @Test
    void indexNamesRemainExplicitAndStable() {
        assertThat(indexesByName().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEX_NAMES);
    }
}
