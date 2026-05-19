package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CUSTOMER_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionCustomerQueryIndexCoverageTest {

    @Test
    void customerFilterHasCursorIndexCoverage() {
        assertThat(keys(CUSTOMER_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("customerId", 1);
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
