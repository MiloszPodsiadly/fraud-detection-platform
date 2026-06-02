package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjection;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionRepository;
import com.frauddetection.alert.persistence.ScoredTransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class EngineIntelligenceReadService {

    private static final int MAX_TRANSACTION_ID_LENGTH = 128;
    private static final Pattern TRANSACTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

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

    private String normalizedTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        String normalized = transactionId.trim();
        if (normalized.length() > MAX_TRANSACTION_ID_LENGTH
                || transactionId.chars().anyMatch(Character::isISOControl)
                || !TRANSACTION_ID_PATTERN.matcher(normalized).matches()) {
            throw new EngineIntelligenceScoredTransactionNotFoundException();
        }
        return normalized;
    }
}
