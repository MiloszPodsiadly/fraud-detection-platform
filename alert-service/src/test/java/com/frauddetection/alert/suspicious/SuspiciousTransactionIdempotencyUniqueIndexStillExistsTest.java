package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.IDEMPOTENCY_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIdempotencyUniqueIndexStillExistsTest {

    @Test
    void transactionAndSourceEventIdempotencyIndexRemainsUnique() {
        assertThat(indexesByName().get(IDEMPOTENCY_INDEX).unique()).isTrue();
        assertThat(keys(IDEMPOTENCY_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("transactionId", 1);
            put("sourceEventId", 1);
        }});
    }
}
