package com.frauddetection.alert.feedback.snapshot;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceProjectionReadUnavailableException;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadService;
import com.frauddetection.alert.feedback.FraudFeedbackRecord;
import com.frauddetection.alert.mapper.EngineIntelligenceResponseMapper;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EngineIntelligenceFeedbackSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceFeedbackSnapshotter.class);

    private final EngineIntelligenceReadService readService;
    private final EngineIntelligenceResponseMapper responseMapper;

    public EngineIntelligenceFeedbackSnapshotter(
            EngineIntelligenceReadService readService,
            EngineIntelligenceResponseMapper responseMapper
    ) {
        this.readService = readService;
        this.responseMapper = responseMapper;
    }

    public void snapshot(FraudFeedbackRecord record, ScoredTransaction transaction) {
        try {
            EngineIntelligenceReadModel readModel = readService.readForOccurrence(
                    transaction.transactionId(),
                    transaction.scoringOccurrenceOwnership()
            );
            EngineIntelligenceResponse response = responseMapper.toResponse(readModel);
            record.setEngineIntelligenceStatus(response.status());
            if (response.comparison() != null) {
                record.setComparisonType(response.comparison().comparisonType());
                record.setComparedEngineIds(response.comparison().comparedEngineIds());
                record.setAgreementStatus(response.comparison().agreementStatus());
                record.setRiskMismatchStatus(response.comparison().riskMismatchStatus());
                record.setScoreDeltaBucket(response.comparison().scoreDeltaBucket());
            }
            snapshotRulesEvidence(record, readModel);
        } catch (EngineIntelligenceProjectionReadUnavailableException exception) {
            record.setEngineIntelligenceStatus(EngineIntelligenceResponseStatus.UNAVAILABLE);
        } catch (RuntimeException exception) {
            log.warn("Fraud feedback engine intelligence snapshot unavailable.");
            record.setEngineIntelligenceStatus(EngineIntelligenceResponseStatus.UNAVAILABLE);
        }
    }

    private void snapshotRulesEvidence(FraudFeedbackRecord record, EngineIntelligenceReadModel readModel) {
        if (readModel == null || !readModel.available() || readModel.engines() == null) {
            return;
        }
        readModel.engines().stream()
                .filter(engine -> FraudEngineIdentityContract.RULES_PRIMARY_ENGINE_ID.equals(engine.engineId()))
                .filter(engine -> engine.engineType() == FraudEngineType.RULES)
                .findFirst()
                .ifPresent(engine -> {
                    record.setRulesEngineStatus(engine.status());
                    record.setRulesRiskLevel(engine.riskLevel());
                });
    }
}
