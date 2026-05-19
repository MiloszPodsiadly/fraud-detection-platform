package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.ALERT_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionLinkedAlertQueryIndexCoverageTest {

    @Test
    void linkedAlertFilterHasNormalCursorIndexCoverage() {
        assertThat(keys(ALERT_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("linkedAlertId", 1);
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
