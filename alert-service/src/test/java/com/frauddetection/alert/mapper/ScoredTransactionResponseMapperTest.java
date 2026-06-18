package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoredTransactionResponseMapperTest {

    private final ScoredTransactionResponseMapper mapper = new ScoredTransactionResponseMapper(new AlertResponseMapper());

    @Test
    void existingListResponseFieldsRemainUnchangedAndLightweight() {
        var response = mapper.toResponse(scoredTransaction());

        assertThat(response.transactionId()).isEqualTo("txn-1");
        assertThat(response.customerId()).isEqualTo("customer-1");
        assertThat(response.fraudScore()).isEqualTo(0.91d);
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(response.reasonCodes()).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void detailResponseAddsDelegatedEngineIntelligence() {
        EngineIntelligenceResponse engineIntelligence = EngineIntelligenceResponse.absent();

        var response = mapper.toDetailResponse(scoredTransaction(), engineIntelligence);

        assertThat(response.engineIntelligence()).isSameAs(engineIntelligence);
    }

    private ScoredTransaction scoredTransaction() {
        return new ScoredTransaction(
                "txn-1",
                "customer-1",
                "correlation-1",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z"),
                null,
                null,
                0.91d,
                RiskLevel.CRITICAL,
                true,
                List.of("HIGH_VELOCITY")
        );
    }
}
