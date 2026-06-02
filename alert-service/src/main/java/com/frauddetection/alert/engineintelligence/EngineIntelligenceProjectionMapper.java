package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class EngineIntelligenceProjectionMapper {

    private final EngineIntelligenceProjectionPolicy policy;
    private final Clock clock;

    @Autowired
    public EngineIntelligenceProjectionMapper(EngineIntelligenceProjectionPolicy policy) {
        this(policy, Clock.systemUTC());
    }

    EngineIntelligenceProjectionMapper(EngineIntelligenceProjectionPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EngineIntelligenceProjectionResult map(
            String transactionId,
            EngineIntelligenceSummary engineIntelligence,
            Instant existingCreatedAt
    ) {
        if (engineIntelligence == null) {
            return EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_ABSENT
            );
        }
        if (transactionId == null || transactionId.isBlank() || transactionId.length() > EngineIntelligenceProjectionPolicy.MAX_STRING_LENGTH) {
            return EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE
            );
        }

        try {
            EngineIntelligenceSummary safe = policy.validatedCopy(engineIntelligence);
            Instant now = clock.instant();
            return EngineIntelligenceProjectionResult.projected(new EngineIntelligenceProjection(
                    transactionId,
                    safe.contractVersion(),
                    safe.generatedAt(),
                    safe.comparison().agreementStatus(),
                    safe.comparison().riskMismatchStatus(),
                    safe.comparison().scoreDeltaBucket(),
                    safe.engines().stream()
                            .map(engine -> new EngineIntelligenceEngineProjection(
                                    engine.engineId(),
                                    engine.engineType(),
                                    engine.status(),
                                    engine.riskLevel(),
                                    engine.scoreBucket(),
                                    engine.reasonCodes()
                            ))
                            .toList(),
                    safe.diagnosticSignals().stream()
                            .map(signal -> new EngineIntelligenceDiagnosticSignalProjection(
                                    signal.engineId(),
                                    signal.engineType(),
                                    signal.engineStatus(),
                                    signal.signalCategory(),
                                    signal.riskLevel(),
                                    signal.scoreBucket(),
                                    signal.reasonCode()
                            ))
                            .toList(),
                    safe.warnings().stream()
                            .map(warning -> new EngineIntelligenceWarningProjection(warning.code(), warning.count()))
                            .toList(),
                    existingCreatedAt == null ? now : existingCreatedAt,
                    now
            ));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return EngineIntelligenceProjectionResult.omitted(classify(exception.getMessage()));
        } catch (RuntimeException exception) {
            return EngineIntelligenceProjectionResult.omitted(
                    EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_PROJECTION_FAILED
            );
        }
    }

    private EngineIntelligenceProjectionOmissionReason classify(String message) {
        if (contains(message, "UNSUPPORTED_CONTRACT_VERSION")) {
            return EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION;
        }
        if (contains(message, "LIMIT_EXCEEDED")) {
            return EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED;
        }
        if (contains(message, "REASON_CODE_NOT_ALLOWED")) {
            return EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED;
        }
        return EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE;
    }

    private boolean contains(String message, String token) {
        return message != null && message.contains(token);
    }
}
