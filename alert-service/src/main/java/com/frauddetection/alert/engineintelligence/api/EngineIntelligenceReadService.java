package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class EngineIntelligenceReadService {

    private final ScoredTransactionRepository scoredTransactionRepository;
    private final EngineIntelligenceProjectionRepository projectionRepository;
    private final EngineIntelligenceReadModelMapper mapper;

    public EngineIntelligenceReadService(
            ScoredTransactionRepository scoredTransactionRepository,
            EngineIntelligenceProjectionRepository projectionRepository,
            EngineIntelligenceReadModelMapper mapper
    ) {
        this.scoredTransactionRepository = Objects.requireNonNull(
                scoredTransactionRepository,
                "scoredTransactionRepository is required"
        );
        this.projectionRepository = Objects.requireNonNull(projectionRepository, "projectionRepository is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    public EngineIntelligenceReadModel read(String transactionId) {
        String boundedTransactionId = normalizedTransactionId(transactionId);
        if (!scoredTransactionRepository.existsById(boundedTransactionId)) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        return projectionRepository.findById(boundedTransactionId)
                .map(mapper::map)
                .orElseGet(() -> EngineIntelligenceReadModel.notProjected(boundedTransactionId));
    }

    private String normalizedTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        return transactionId.trim();
    }
}
