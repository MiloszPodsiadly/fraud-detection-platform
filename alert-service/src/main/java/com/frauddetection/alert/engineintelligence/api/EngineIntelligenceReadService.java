package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(transactionManager = "mongoTransactionManager", readOnly = true)
    public EngineIntelligenceReadModel read(String transactionId) {
        return read(transactionId, null);
    }

    @Transactional(transactionManager = "mongoTransactionManager", readOnly = true)
    public EngineIntelligenceReadModel readForOccurrence(
            String transactionId,
            ScoringOccurrenceOwnership expectedOwnership
    ) {
        return read(transactionId, Objects.requireNonNull(expectedOwnership, "expectedOwnership is required"));
    }

    private EngineIntelligenceReadModel read(
            String transactionId,
            ScoringOccurrenceOwnership expectedOwnership
    ) {
        String boundedTransactionId = EngineIntelligenceTransactionIdPolicy.normalize(transactionId);
        ScoredTransactionDocument current;
        try {
            current = scoredTransactionRepository.findById(boundedTransactionId).orElse(null);
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceProjectionReadUnavailableException();
        }
        if (current == null) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        ScoringOccurrenceOwnership ownership;
        try {
            ownership = ScoringOccurrenceOwnership.fromPersistedIdentity(
                    current.getSourceEventId(),
                    current.getSourceEventCreatedAt(),
                    current.getSourceEventCreatedAtEpochSecond(),
                    current.getSourceEventCreatedAtNano(),
                    current.getSourceEventFingerprint()
            );
        } catch (IllegalArgumentException exception) {
            throw new EngineIntelligenceProjectionReadUnavailableException();
        }
        if (expectedOwnership != null && !expectedOwnership.equals(ownership)) {
            return EngineIntelligenceReadModel.notProjected(boundedTransactionId);
        }
        Optional<EngineIntelligenceProjection> projection;
        try {
            projection = projectionRepository.findById(boundedTransactionId);
        } catch (RuntimeException exception) {
            throw new EngineIntelligenceProjectionReadUnavailableException();
        }
        return projection
                .filter(candidate -> ownership.state() == ScoringOccurrenceOwnership.State.AUTHORITATIVE)
                .filter(candidate -> Objects.equals(candidate.getSourceEventId(), ownership.sourceEventId()))
                .filter(candidate -> Objects.equals(
                        candidate.getSourceEventCreatedAt(),
                        ownership.sourceEventCreatedAt()
                ))
                .filter(candidate -> Objects.equals(
                        candidate.getSourceEventFingerprint(),
                        ownership.sourceEventFingerprint()
                ))
                .map(mapper::map)
                .orElseGet(() -> EngineIntelligenceReadModel.notProjected(boundedTransactionId));
    }
}
