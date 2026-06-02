package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Component
public class EngineIntelligenceProjectionPolicy {

    public static final int MAX_ENGINES = 2;
    public static final int MAX_DIAGNOSTIC_SIGNALS = 5;
    public static final int MAX_WARNINGS = 10;
    public static final int MAX_REASON_CODES_PER_ENGINE = 5;
    public static final int MAX_STRING_LENGTH = 128;

    public EngineIntelligenceSummary validatedCopy(EngineIntelligenceSummary source) {
        Objects.requireNonNull(source, "engineIntelligence is required");
        if (source.contractVersion() != EngineIntelligenceSummary.CONTRACT_VERSION) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        }
        return new EngineIntelligenceSummary(
                source.contractVersion(),
                source.generatedAt(),
                copyBounded(source.engines(), MAX_ENGINES, "ENGINES", this::validatedEngine),
                validatedComparison(source.comparison()),
                copyBounded(
                        source.diagnosticSignals(),
                        MAX_DIAGNOSTIC_SIGNALS,
                        "DIAGNOSTIC_SIGNALS",
                        this::validatedDiagnosticSignal
                ),
                copyBounded(source.warnings(), MAX_WARNINGS, "WARNINGS", this::validatedWarning)
        );
    }

    private EngineIntelligenceEngineResult validatedEngine(EngineIntelligenceEngineResult source) {
        Objects.requireNonNull(source, "engine must not be null");
        return new EngineIntelligenceEngineResult(
                source.engineId(),
                source.engineType(),
                source.status(),
                source.riskLevel(),
                source.scoreBucket(),
                copyBounded(source.reasonCodes(), MAX_REASON_CODES_PER_ENGINE, "REASON_CODES", Function.identity())
        );
    }

    private EngineIntelligenceComparison validatedComparison(EngineIntelligenceComparison source) {
        Objects.requireNonNull(source, "comparison is required");
        return new EngineIntelligenceComparison(
                source.agreementStatus(),
                source.riskMismatchStatus(),
                source.scoreDeltaBucket()
        );
    }

    private EngineIntelligenceDiagnosticSignal validatedDiagnosticSignal(EngineIntelligenceDiagnosticSignal source) {
        Objects.requireNonNull(source, "diagnostic signal must not be null");
        return new EngineIntelligenceDiagnosticSignal(
                source.engineId(),
                source.engineType(),
                source.engineStatus(),
                source.signalCategory(),
                source.riskLevel(),
                source.scoreBucket(),
                source.reasonCode()
        );
    }

    private EngineIntelligenceWarningSummary validatedWarning(EngineIntelligenceWarningSummary source) {
        Objects.requireNonNull(source, "warning must not be null");
        return new EngineIntelligenceWarningSummary(source.code(), source.count());
    }

    private <T, R> List<R> copyBounded(
            List<T> source,
            int maximum,
            String fieldName,
            Function<T, R> mapper
    ) {
        Objects.requireNonNull(source, fieldName + " is required");
        if (source.size() > maximum) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_" + fieldName + "_LIMIT_EXCEEDED");
        }
        return source.stream().map(mapper).toList();
    }
}
