package com.frauddetection.common.events.engine;

import tools.jackson.databind.ObjectMapper;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineResultCompatibilityTest {

    @Test
    void existingTransactionScoredEventStillSerializes() throws Exception {
        String json = objectMapper().writeValueAsString(scoredEvent());

        assertThat(json)
                .contains("\"transactionId\":\"txn-1\"")
                .contains("\"riskLevel\":\"HIGH\"")
                .doesNotContain("FraudEngineResult")
                .doesNotContain("engineResults");
    }

    @Test
    void existingTransactionScoredEventStillDeserializes() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(objectMapper().writeValueAsString(scoredEvent()),
                TransactionScoredEvent.class);

        assertThat(event.transactionId()).isEqualTo("txn-1");
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void addingFraudEngineResultTypesDoesNotChangeCurrentEventShape() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("engineResults", "fraudEngineResults", "fraudEngineResult")
                .contains("engineIntelligence");
    }

    @Test
    void commonEventsBuildsWithoutAlertServiceDependency() {
        assertThat(FraudEngineResult.class.getPackageName())
                .isEqualTo("com.frauddetection.common.events.engine");
        assertThat(Arrays.stream(FraudEngineResult.class.getRecordComponents())
                .map(RecordComponent::getType)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("com.frauddetection.alert."));
    }

    private TransactionScoredEvent scoredEvent() {
        return new TransactionScoredEvent(
                "event-1",
                "txn-1",
                "corr-1",
                "customer-1",
                "account-1",
                Instant.parse("2026-06-01T10:15:30Z"),
                Instant.parse("2026-06-01T10:14:30Z"),
                null,
                null,
                null,
                null,
                null,
                0.91d,
                RiskLevel.HIGH,
                "RULE_BASED",
                "rules",
                "v1",
                Instant.parse("2026-06-01T10:15:30Z"),
                List.of("HIGH_VELOCITY"),
                Map.of(),
                Map.of(),
                true,
                List.of(),
                null
        );
    }

    private ObjectMapper objectMapper() {
        return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
    }
}
