package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class EngineIntelligencePendingProjectionService {

    private final MongoTemplate mongoTemplate;
    private final EngineIntelligenceProjectionPolicy projectionPolicy;

    public EngineIntelligencePendingProjectionService(
            MongoTemplate mongoTemplate,
            EngineIntelligenceProjectionPolicy projectionPolicy
    ) {
        this.mongoTemplate = mongoTemplate;
        this.projectionPolicy = projectionPolicy;
    }

    public void defer(TransactionScoredEvent event) {
        defer(event, null);
    }

    public void deferRecovery(
            TransactionScoredEvent event,
            EngineIntelligenceRecoveryProvenance provenance
    ) {
        if (provenance == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_RECOVERY_PROVENANCE_REQUIRED");
        }
        defer(event, provenance);
    }

    private void defer(
            TransactionScoredEvent event,
            EngineIntelligenceRecoveryProvenance provenance
    ) {
        if (event == null || event.createdAt() == null || event.engineIntelligence() == null) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_PENDING_INPUT_INVALID");
        }
        EngineIntelligencePendingProjection candidate = new EngineIntelligencePendingProjection();
        candidate.setSourceEventId(projectionPolicy.validatedSourceEventId(event.eventId()));
        candidate.setTransactionId(projectionPolicy.validatedTransactionId(event.transactionId()));
        candidate.setSourceEventCreatedAt(event.createdAt());
        candidate.setSourceEventFingerprint(ScoringOccurrenceFingerprint.from(event));
        candidate.setEngineIntelligence(projectionPolicy.validatedCopy(event.engineIntelligence()));
        candidate.setRecoveryProvenance(provenance);
        candidate.setStatus(EngineIntelligencePendingProjectionStatus.PENDING);
        Instant now = Instant.now();
        candidate.setAvailableAt(now);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        try {
            mongoTemplate.insert(candidate);
        } catch (DuplicateKeyException duplicate) {
            EngineIntelligencePendingProjection existing = mongoTemplate.findById(
                    candidate.getSourceEventId(),
                    EngineIntelligencePendingProjection.class
            );
            if (!sameWork(existing, candidate)) {
                throw new EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException();
            }
        }
    }

    private boolean sameWork(
            EngineIntelligencePendingProjection existing,
            EngineIntelligencePendingProjection candidate
    ) {
        return existing != null
                && Objects.equals(existing.getTransactionId(), candidate.getTransactionId())
                && Objects.equals(existing.getSourceEventCreatedAtText(), candidate.getSourceEventCreatedAtText())
                && Objects.equals(
                        existing.getSourceEventCreatedAtEpochSecond(),
                        candidate.getSourceEventCreatedAtEpochSecond()
                )
                && Objects.equals(existing.getSourceEventCreatedAtNano(), candidate.getSourceEventCreatedAtNano())
                && Objects.equals(existing.getSourceEventFingerprint(), candidate.getSourceEventFingerprint())
                && Objects.equals(existing.getEngineIntelligence(), candidate.getEngineIntelligence())
                && Objects.equals(existing.getRecoveryProvenance(), candidate.getRecoveryProvenance());
    }
}
