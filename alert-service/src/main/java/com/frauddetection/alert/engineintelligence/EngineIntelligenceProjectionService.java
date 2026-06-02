package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class EngineIntelligenceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceProjectionService.class);

    private final EngineIntelligenceProjectionRepository repository;
    private final EngineIntelligenceProjectionMapper mapper;

    public EngineIntelligenceProjectionService(
            EngineIntelligenceProjectionRepository repository,
            EngineIntelligenceProjectionMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public EngineIntelligenceProjectionResult project(TransactionScoredEvent event) {
        if (event == null || event.engineIntelligence() == null) {
            return mapper.map(event == null ? null : event.transactionId(), null, null);
        }

        try {
            Instant createdAt = repository.findById(event.transactionId())
                    .map(EngineIntelligenceProjection::getCreatedAt)
                    .orElse(null);
            EngineIntelligenceProjectionResult result = mapper.map(
                    event.transactionId(),
                    event.engineIntelligence(),
                    createdAt
            );
            result.projection().ifPresent(repository::save);
            logOmission(result);
            return result;
        } catch (RuntimeException exception) {
            EngineIntelligenceProjectionResult result = EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
            logOmission(result);
            return result;
        }
    }

    private void logOmission(EngineIntelligenceProjectionResult result) {
        result.omissionReason().ifPresent(reason -> log.atWarn()
                .addKeyValue("reason", reason)
                .log("Engine intelligence internal projection omitted."));
    }
}
