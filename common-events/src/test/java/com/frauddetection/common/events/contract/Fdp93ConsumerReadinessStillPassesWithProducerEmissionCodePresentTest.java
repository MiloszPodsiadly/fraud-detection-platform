package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Fdp93ConsumerReadinessStillPassesWithProducerEmissionCodePresentTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void oldEventWithoutEngineIntelligenceStillDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.oldWithoutEngineIntelligenceJson()).engineIntelligence())
                .isNull();
    }

    @Test
    void boundedEventWithEngineIntelligenceStillDeserializes() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.fullBoundedEngineIntelligenceJson()).engineIntelligence())
                .isNotNull();
    }

    @Test
    void forwardCompatibleFixturesStillDeserialize() throws Exception {
        assertThat(read(TransactionScoredEventFixtureLoader.unknownNestedEngineIntelligenceFieldsJson())
                .engineIntelligence()).isNotNull();
        assertThat(read(TransactionScoredEventFixtureLoader.unknownTopLevelFieldJson()).transactionId())
                .isEqualTo("txn-fdp93-001");
    }

    private TransactionScoredEvent read(String json) throws Exception {
        return objectMapper.readValue(json, TransactionScoredEvent.class);
    }
}
