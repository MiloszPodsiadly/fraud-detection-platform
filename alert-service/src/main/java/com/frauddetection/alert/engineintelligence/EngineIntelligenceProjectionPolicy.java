package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Component
public class EngineIntelligenceProjectionPolicy {

    public static final int MAX_ENGINES = 2;
    public static final int MAX_DIAGNOSTIC_SIGNALS = 5;
    public static final int MAX_WARNINGS = 10;
    public static final int MAX_REASON_CODES_PER_ENGINE = 5;
    public static final int MAX_STRING_LENGTH = 128;

    private static final Set<String> ALLOWED_ENGINE_IDS = Set.of("rules.primary", "ml.python.primary");
    private static final Set<String> FORBIDDEN_COMPACT_TEXT = Set.of(
            "rawevidence",
            "rawcontribution",
            "featuresnapshot",
            "featurevector",
            "rawpayload",
            "payload",
            "endpoint",
            "token",
            "secret",
            "stacktrace",
            "exception",
            "internalaggregation",
            "fraudengineaggregationresult",
            "normalizedfraudengineresult",
            "scoringcontext",
            "rawmlresponse",
            "rawmodel",
            "debug"
    );

    String validatedTransactionId(String transactionId) {
        return boundedString(transactionId, "TRANSACTION_ID");
    }

    public EngineIntelligenceSummary validatedCopy(EngineIntelligenceSummary source) {
        Objects.requireNonNull(source, "engineIntelligence is required");
        if (source.contractVersion() != EngineIntelligenceSummary.CONTRACT_VERSION) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION");
        }
        // Alert-service revalidates engine intelligence by reconstructing FDP-92 public DTOs. This keeps the
        // public event contract as the source of truth for enum/value allowlists while FDP-95 adds
        // storage-specific size and omission safeguards.
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
                allowedEngineId(source.engineId()),
                boundedEnum(source.engineType(), FraudEngineType.class, "ENGINE_TYPE"),
                boundedEnum(source.status(), FraudEngineStatus.class, "ENGINE_STATUS"),
                boundedOptionalEnum(source.riskLevel(), RiskLevel.class, "RISK_LEVEL"),
                boundedEnum(source.scoreBucket(), EngineIntelligenceScoreBucket.class, "SCORE_BUCKET"),
                copyBounded(source.reasonCodes(), MAX_REASON_CODES_PER_ENGINE, "REASON_CODES", this::boundedReasonCode)
        );
    }

    private EngineIntelligenceComparison validatedComparison(EngineIntelligenceComparison source) {
        Objects.requireNonNull(source, "comparison is required");
        return new EngineIntelligenceComparison(
                boundedEnum(source.agreementStatus(), EngineIntelligenceAgreementStatus.class, "AGREEMENT_STATUS"),
                boundedEnum(source.riskMismatchStatus(), EngineIntelligenceRiskMismatchStatus.class, "RISK_MISMATCH_STATUS"),
                boundedEnum(source.scoreDeltaBucket(), EngineIntelligenceScoreDeltaBucket.class, "SCORE_DELTA_BUCKET")
        );
    }

    private EngineIntelligenceDiagnosticSignal validatedDiagnosticSignal(EngineIntelligenceDiagnosticSignal source) {
        Objects.requireNonNull(source, "diagnostic signal must not be null");
        return new EngineIntelligenceDiagnosticSignal(
                allowedEngineId(source.engineId()),
                boundedEnum(source.engineType(), FraudEngineType.class, "DIAGNOSTIC_ENGINE_TYPE"),
                boundedEnum(source.engineStatus(), FraudEngineStatus.class, "DIAGNOSTIC_ENGINE_STATUS"),
                boundedEnum(source.signalCategory(), EngineIntelligenceSignalCategory.class, "DIAGNOSTIC_SIGNAL_TYPE"),
                boundedOptionalEnum(source.riskLevel(), RiskLevel.class, "DIAGNOSTIC_RISK_LEVEL"),
                boundedEnum(source.scoreBucket(), EngineIntelligenceScoreBucket.class, "DIAGNOSTIC_SCORE_BUCKET"),
                boundedReasonCode(source.reasonCode())
        );
    }

    private EngineIntelligenceWarningSummary validatedWarning(EngineIntelligenceWarningSummary source) {
        Objects.requireNonNull(source, "warning must not be null");
        return new EngineIntelligenceWarningSummary(
                boundedEnum(source.code(), EngineIntelligenceWarningCode.class, "WARNING_CODE"),
                source.count()
        );
    }

    private String allowedEngineId(String engineId) {
        String safe = boundedString(engineId, "ENGINE_ID");
        if (!ALLOWED_ENGINE_IDS.contains(safe)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_ENGINE_ID_INVALID");
        }
        return safe;
    }

    private String boundedReasonCode(String reasonCode) {
        return boundedString(reasonCode, "REASON_CODE");
    }

    private <T extends Enum<T>> T boundedEnum(T value, Class<T> enumType, String fieldName) {
        Objects.requireNonNull(value, "ENGINE_INTELLIGENCE_" + fieldName + "_INVALID");
        boundedString(value.toString(), fieldName);
        if (!List.of(enumType.getEnumConstants()).contains(value)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_" + fieldName + "_INVALID");
        }
        return value;
    }

    private <T extends Enum<T>> T boundedOptionalEnum(T value, Class<T> enumType, String fieldName) {
        if (value == null) {
            return null;
        }
        return boundedEnum(value, enumType, fieldName);
    }

    private String boundedString(String value, String fieldName) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_STRING_LENGTH
                || value.chars().anyMatch(Character::isISOControl)
                || containsForbiddenText(value)) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_" + fieldName + "_INVALID");
        }
        return value;
    }

    private boolean containsForbiddenText(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return FORBIDDEN_COMPACT_TEXT.stream().anyMatch(compact::contains);
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
