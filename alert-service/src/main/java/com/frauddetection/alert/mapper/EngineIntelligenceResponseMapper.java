package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceDiagnosticSignalResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatusPolicy;
import com.frauddetection.alert.api.EngineIntelligenceWarningResponse;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EngineIntelligenceResponseMapper {

    static final int MAX_PUBLIC_ENGINES = FraudEngineIdentityContract.MAX_ENGINE_INTELLIGENCE_ENGINES;
    static final int MAX_PUBLIC_DIAGNOSTIC_SIGNALS = 5;
    static final int MAX_PUBLIC_WARNINGS = 10;
    static final int MAX_PUBLIC_REASON_CODES = 5;

    public EngineIntelligenceResponse toResponse(EngineIntelligenceReadModel readModel) {
        // available=false is reserved for not-projected/absent records. Projection read failures
        // stay on the controller path and are exposed as UNAVAILABLE, not ABSENT.
        if (readModel == null || !readModel.available()) {
            return EngineIntelligenceResponse.absent();
        }
        try {
            EngineIntelligenceComparisonResponse comparison = comparison(readModel);
            List<EngineIntelligenceEngineResponse> engines = engines(readModel);
            List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals = diagnosticSignals(readModel);
            List<EngineIntelligenceWarningResponse> warnings = warnings(readModel);
            return new EngineIntelligenceResponse(
                    EngineIntelligenceResponseStatusPolicy.derive(engines, warnings),
                    readModel.contractVersion(),
                    readModel.generatedAt(),
                    comparison,
                    engines,
                    diagnosticSignals,
                    warnings
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            return EngineIntelligenceResponse.unavailable();
        }
    }

    public EngineIntelligenceResponse unavailable() {
        return EngineIntelligenceResponse.unavailable();
    }

    private EngineIntelligenceComparisonResponse comparison(EngineIntelligenceReadModel readModel) {
        if (readModel.comparison() == null) {
            return null;
        }
        return new EngineIntelligenceComparisonResponse(
                readModel.comparison().comparisonType(),
                readModel.comparison().comparedEngineIds(),
                readModel.comparison().agreementStatus(),
                readModel.comparison().riskMismatchStatus(),
                readModel.comparison().scoreDeltaBucket()
        );
    }

    private List<EngineIntelligenceEngineResponse> engines(EngineIntelligenceReadModel readModel) {
        return list(readModel.engines()).stream()
                .limit(MAX_PUBLIC_ENGINES)
                .map(engine -> new EngineIntelligenceEngineResponse(
                        engine.engineId(),
                        engine.engineType(),
                        publicStatus(engine.status()),
                        engine.riskLevel(),
                        engine.scoreBucket(),
                        list(engine.reasonCodes()).stream()
                                .limit(MAX_PUBLIC_REASON_CODES)
                                .toList()
                ))
                .toList();
    }

    private List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals(EngineIntelligenceReadModel readModel) {
        return list(readModel.diagnosticSignals()).stream()
                .limit(MAX_PUBLIC_DIAGNOSTIC_SIGNALS)
                .map(signal -> new EngineIntelligenceDiagnosticSignalResponse(
                        signal.engineId(),
                        signal.engineType(),
                        publicStatus(signal.engineStatus()),
                        signal.signalCategory(),
                        signal.riskLevel(),
                        signal.scoreBucket(),
                        signal.reasonCode()
                ))
                .toList();
    }

    private List<EngineIntelligenceWarningResponse> warnings(EngineIntelligenceReadModel readModel) {
        return list(readModel.warnings()).stream()
                .limit(MAX_PUBLIC_WARNINGS)
                .map(warning -> new EngineIntelligenceWarningResponse(warning.warningCode(), warning.count()))
                .toList();
    }

    private <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private EngineIntelligenceEngineStatusResponse publicStatus(FraudEngineStatus status) {
        if (status == null) {
            return EngineIntelligenceEngineStatusResponse.UNAVAILABLE;
        }
        return switch (status) {
            case AVAILABLE -> EngineIntelligenceEngineStatusResponse.AVAILABLE;
            case TIMEOUT -> EngineIntelligenceEngineStatusResponse.TIMEOUT;
            case DEGRADED, FALLBACK_USED -> EngineIntelligenceEngineStatusResponse.DEGRADED;
            case SKIPPED -> EngineIntelligenceEngineStatusResponse.NOT_APPLICABLE;
            case UNAVAILABLE -> EngineIntelligenceEngineStatusResponse.UNAVAILABLE;
        };
    }
}
