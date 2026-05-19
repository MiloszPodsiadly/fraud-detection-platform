package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.IDEMPOTENCY_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIndexesDoNotRenameExistingIdempotencyIndexTest {

    @Test
    void existingIdempotencyIndexNameIsPreserved() {
        assertThat(indexesByName()).containsKey(IDEMPOTENCY_INDEX);
    }
}
