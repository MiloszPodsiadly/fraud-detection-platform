package com.frauddetection.alert.consumer;

import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceEngineIntelligencePayloadToleranceTest {

    private static final int SAFE_FIXTURE_THRESHOLD_BYTES = 8 * 1024;
    private final ScoredTransactionDocumentMapper mapper = new ScoredTransactionDocumentMapper();

    @Test
    void alertServiceToleratesFullBoundedPayloadWithoutProjectionExpansion() {
        String fixture = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson();
        TransactionScoredEvent full = AlertServiceTransactionScoredEventFixtureLoader.fullBoundedEngineIntelligence();

        assertThat(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThan(SAFE_FIXTURE_THRESHOLD_BYTES);
        assertThat(full.engineIntelligence()).isNotNull();
        assertThat(mapper.toDocument(full))
                .usingRecursiveComparison()
                .isEqualTo(mapper.toDocument(AlertServiceTransactionScoredEventFixtureLoader.oldWithoutEngineIntelligence()));
        assertThat(full.engineIntelligence().toString())
                .doesNotContainIgnoringCase("rawPayload", "rawEvidence", "finalDecision", "recommendedAction");
    }
}
