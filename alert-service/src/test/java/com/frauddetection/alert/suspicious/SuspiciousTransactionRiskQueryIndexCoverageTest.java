package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.RISK_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionRiskQueryIndexCoverageTest {

    @Test
    void riskFilterHasCursorIndexCoverage() {
        assertThat(keys(RISK_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("riskLevel", 1);
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
