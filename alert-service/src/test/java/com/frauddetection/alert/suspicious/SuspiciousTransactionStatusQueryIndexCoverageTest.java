package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.STATUS_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionStatusQueryIndexCoverageTest {

    @Test
    void statusFilterHasCursorIndexCoverage() {
        assertThat(keys(STATUS_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("status", 1);
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
