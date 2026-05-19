package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CURSOR_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionCursorQueryIndexCoverageTest {

    @Test
    void unfilteredCursorReadHasDetectedAtAndIdIndexCoverage() {
        assertThat(keys(CURSOR_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
