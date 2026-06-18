package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceComparisonResponse;
import com.frauddetection.alert.api.EngineIntelligenceDiagnosticSignalResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineResponse;
import com.frauddetection.alert.api.EngineIntelligenceEngineStatusResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.api.EngineIntelligenceWarningResponse;
import com.frauddetection.alert.engineintelligence.api.EngineIntelligenceReadModel;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EngineIntelligenceResponseMapper {

    public EngineIntelligenceResponse toResponse(EngineIntelligenceReadModel readModel) {
        if (readModel == null || !readModel.available()) {
            return EngineIntelligenceResponse.absent();
        }
        return new EngineIntelligenceResponse(
                status(readModel),
                readModel.contractVersion(),
                readModel.generatedAt(),
                comparison(readModel),
                engines(readModel),
                diagnosticSignals(readModel),
                warnings(readModel)
        );
    }

    public EngineIntelligenceResponse unavailable() {
        return EngineIntelligenceResponse.unavailable();
    }

    private EngineIntelligenceResponseStatus status(EngineIntelligenceReadModel readModel) {
        if (hasLimitedProjectionData(readModel)) {
            return EngineIntelligenceResponseStatus.DEGRADED;
        }
        return EngineIntelligenceResponseStatus.AVAILABLE;
    }

    private boolean hasLimitedProjectionData(EngineIntelligenceReadModel readModel) {
        return readModel.engines() != null && readModel.engines().stream()
                .anyMatch(engine -> engine.status() == FraudEngineStatus.DEGRADED
                        || engine.status() == FraudEngineStatus.TIMEOUT
                        || engine.status() == FraudEngineStatus.UNAVAILABLE);
    }

    private EngineIntelligenceComparisonResponse comparison(EngineIntelligenceReadModel readModel) {
        if (readModel.comparison() == null) {
            return null;
        }
        return new EngineIntelligenceComparisonResponse(
                readModel.comparison().agreementStatus(),
                readModel.comparison().riskMismatchStatus(),
                readModel.comparison().scoreDeltaBucket()
        );
    }

    private List<EngineIntelligenceEngineResponse> engines(EngineIntelligenceReadModel readModel) {
        return list(readModel.engines()).stream()
                .map(engine -> new EngineIntelligenceEngineResponse(
                        engine.engineId(),
                        engine.engineType(),
                        publicStatus(engine.status()),
                        engine.riskLevel(),
                        engine.scoreBucket(),
                        engine.reasonCodes()
                ))
                .toList();
    }

    private List<EngineIntelligenceDiagnosticSignalResponse> diagnosticSignals(EngineIntelligenceReadModel readModel) {
        return list(readModel.diagnosticSignals()).stream()
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
