package com.frauddetection.alert.suspicious;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.ALERT_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CUSTOMER_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.CURSOR_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.EXPECTED_INDEX_NAMES;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.RISK_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.STATUS_INDEX;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.indexesByName;
import static com.frauddetection.alert.suspicious.SuspiciousTransactionIndexTestSupport.keys;
import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTransactionIndexDefinitionTest {

    @Test
    void declaresOnlyFdp63SupportedReadIndexesAndIdempotencyIndex() {
        assertThat(indexesByName().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEX_NAMES);
    }

    @Test
    void cursorIndexesUseMongoIdFieldForIdTieBreaker() {
        assertThat(keys(CURSOR_INDEX)).containsEntry("_id", -1);
        assertThat(keys(STATUS_INDEX)).containsEntry("_id", -1);
        assertThat(keys(RISK_INDEX)).containsEntry("_id", -1);
        assertThat(keys(CUSTOMER_INDEX)).containsEntry("_id", -1);
        assertThat(keys(ALERT_INDEX)).containsEntry("_id", -1);
    }

    @Test
    void cursorIndexSupportsDetectedAtDescendingAndIdDescending() {
        assertThat(keys(CURSOR_INDEX)).isEqualTo(new LinkedHashMap<>() {{
            put("detectedAt", -1);
            put("_id", -1);
        }});
    }
}
