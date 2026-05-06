package com.frauddetection.alert.regulated.chaos;

import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Objects;
import java.util.function.Consumer;

public record RegulatedMutationChaosScenario(
        String name,
        RegulatedMutationChaosWindow window,
        String commandId,
        String idempotencyKey,
        Consumer<MongoTemplate> seedDurableState
) {
    public RegulatedMutationChaosScenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(seedDurableState, "seedDurableState");
    }
}
