package com.frauddetection.alert.regulated.chaos;

import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Objects;
import java.util.function.Consumer;

public record RegulatedMutationChaosScenario(
        String name,
        RegulatedMutationChaosWindow window,
        RegulatedMutationStateReachMethod stateReachMethod,
        String commandId,
        String idempotencyKey,
        Consumer<MongoTemplate> seedDurableState
) {
    public RegulatedMutationChaosScenario(
            String name,
            RegulatedMutationChaosWindow window,
            String commandId,
            String idempotencyKey,
            Consumer<MongoTemplate> seedDurableState
    ) {
        this(name, window, RegulatedMutationStateReachMethod.DURABLE_STATE_SEEDED, commandId, idempotencyKey, seedDurableState);
    }

    public RegulatedMutationChaosScenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(stateReachMethod, "stateReachMethod");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(seedDurableState, "seedDurableState");
    }
}
