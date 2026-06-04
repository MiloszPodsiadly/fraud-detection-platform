package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

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
        String boundedTransactionId = EngineIntelligenceTransactionIdPolicy.normalize(transactionId);
        if (!scoredTransactionRepository.existsById(boundedTransactionId)) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        Optional<EngineIntelligenceProjection> projection;
        try {
            projection = projectionRepository.findById(boundedTransactionId);
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceProjectionReadUnavailableException();
        }
        return projection
                .map(mapper::map)
                .orElseGet(() -> EngineIntelligenceReadModel.notProjected(boundedTransactionId));
    }
}
