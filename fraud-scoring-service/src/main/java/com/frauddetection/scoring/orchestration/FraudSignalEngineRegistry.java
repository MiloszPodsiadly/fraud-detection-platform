package com.frauddetection.scoring.orchestration;

import com.frauddetection.scoring.engine.FraudEngineDescriptor;
import com.frauddetection.scoring.engine.FraudSignalEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FraudSignalEngineRegistry {
    static final String RULES_PRIMARY_ENGINE_ID = "rules.primary";
    static final String PYTHON_ML_PRIMARY_ENGINE_ID = "ml.python.primary";

    private static final List<String> KNOWN_ENGINE_ORDER = List.of(
            RULES_PRIMARY_ENGINE_ID,
            PYTHON_ML_PRIMARY_ENGINE_ID
    );

    private final List<RegisteredEngine> engines;

    public FraudSignalEngineRegistry(List<FraudSignalEngine> engines) {
        Objects.requireNonNull(engines, "engines is required");
        List<RegisteredEngine> registeredEngines = new ArrayList<>();
        Set<String> engineIds = new HashSet<>();
        for (FraudSignalEngine engine : engines) {
            if (engine == null) {
                throw new IllegalArgumentException("ENGINE_REGISTRY_NULL_ENGINE");
            }
            FraudEngineDescriptor descriptor = descriptorFor(engine);
            String engineId = descriptor.engineId();
            if (!KNOWN_ENGINE_ORDER.contains(engineId)) {
                throw new IllegalArgumentException("ENGINE_REGISTRY_UNKNOWN_ENGINE_ID");
            }
            if (!engineIds.add(engineId)) {
                throw new IllegalArgumentException("ENGINE_REGISTRY_DUPLICATE_ENGINE_ID");
            }
            registeredEngines.add(new RegisteredEngine(engine, descriptor));
        }
        registeredEngines.sort(Comparator.comparingInt(engine -> KNOWN_ENGINE_ORDER.indexOf(engine.descriptor().engineId())));
        this.engines = List.copyOf(registeredEngines);
    }

    public List<FraudSignalEngine> orderedEngines() {
        return engines.stream()
                .map(RegisteredEngine::engine)
                .toList();
    }

    List<RegisteredEngine> registeredEngines() {
        return engines;
    }

    private FraudEngineDescriptor descriptorFor(FraudSignalEngine engine) {
        FraudEngineDescriptor descriptor;
        try {
            descriptor = engine.descriptor();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("ENGINE_REGISTRY_INVALID_DESCRIPTOR");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("ENGINE_REGISTRY_NULL_DESCRIPTOR");
        }
        if (descriptor.engineId().isBlank()) {
            throw new IllegalArgumentException("ENGINE_REGISTRY_BLANK_ENGINE_ID");
        }
        return descriptor;
    }

    record RegisteredEngine(FraudSignalEngine engine, FraudEngineDescriptor descriptor) {
    }
}
