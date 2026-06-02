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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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

    public String validatedTransactionId(String transactionId) {
        return boundedString(transactionId);
    }

    public EngineIntelligenceSummary validatedCopy(EngineIntelligenceSummary source) {
        requireShape(source);
        if (source.contractVersion() != EngineIntelligenceSummary.CONTRACT_VERSION) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_UNSUPPORTED_CONTRACT_VERSION);
        }
        // Alert-service projection revalidates engine intelligence through the shared bounded public event
        // contract. This avoids maintaining a divergent second source of truth for public reason codes and
        // enum semantics while FDP-95 adds storage-specific size and omission safeguards.
        return publicContract(() -> new EngineIntelligenceSummary(
                source.contractVersion(),
                source.generatedAt(),
                copyBounded(source.engines(), MAX_ENGINES, this::validatedEngine),
                validatedComparison(source.comparison()),
                copyBounded(
                        source.diagnosticSignals(),
                        MAX_DIAGNOSTIC_SIGNALS,
                        this::validatedDiagnosticSignal
                ),
                copyBounded(source.warnings(), MAX_WARNINGS, this::validatedWarning)
        ));
    }

    private EngineIntelligenceEngineResult validatedEngine(EngineIntelligenceEngineResult source) {
        requireShape(source);
        return publicContract(() -> new EngineIntelligenceEngineResult(
                allowedEngineId(source.engineId()),
                boundedEnum(source.engineType(), FraudEngineType.class),
                boundedEnum(source.status(), FraudEngineStatus.class),
                boundedOptionalEnum(source.riskLevel(), RiskLevel.class),
                boundedEnum(source.scoreBucket(), EngineIntelligenceScoreBucket.class),
                copyBounded(source.reasonCodes(), MAX_REASON_CODES_PER_ENGINE, this::boundedPublicReasonCode)
        ));
    }

    private EngineIntelligenceComparison validatedComparison(EngineIntelligenceComparison source) {
        requireShape(source);
        return publicContract(() -> new EngineIntelligenceComparison(
                boundedEnum(source.agreementStatus(), EngineIntelligenceAgreementStatus.class),
                boundedEnum(source.riskMismatchStatus(), EngineIntelligenceRiskMismatchStatus.class),
                boundedEnum(source.scoreDeltaBucket(), EngineIntelligenceScoreDeltaBucket.class)
        ));
    }

    private EngineIntelligenceDiagnosticSignal validatedDiagnosticSignal(EngineIntelligenceDiagnosticSignal source) {
        requireShape(source);
        return publicContract(() -> new EngineIntelligenceDiagnosticSignal(
                allowedEngineId(source.engineId()),
                boundedEnum(source.engineType(), FraudEngineType.class),
                boundedEnum(source.engineStatus(), FraudEngineStatus.class),
                boundedEnum(source.signalCategory(), EngineIntelligenceSignalCategory.class),
                boundedOptionalEnum(source.riskLevel(), RiskLevel.class),
                boundedEnum(source.scoreBucket(), EngineIntelligenceScoreBucket.class),
                boundedPublicReasonCode(source.reasonCode())
        ));
    }

    private EngineIntelligenceWarningSummary validatedWarning(EngineIntelligenceWarningSummary source) {
        requireShape(source);
        return publicContract(() -> new EngineIntelligenceWarningSummary(
                boundedEnum(source.code(), EngineIntelligenceWarningCode.class),
                source.count()
        ));
    }

    private String allowedEngineId(String engineId) {
        String safe = boundedString(engineId);
        if (!ALLOWED_ENGINE_IDS.contains(safe)) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
        return safe;
    }

    private String boundedPublicReasonCode(String reasonCode) {
        String safe = boundedString(reasonCode);
        try {
            new EngineIntelligenceEngineResult(
                    "rules.primary",
                    FraudEngineType.RULES,
                    FraudEngineStatus.AVAILABLE,
                    RiskLevel.LOW,
                    EngineIntelligenceScoreBucket.LOW,
                    List.of(safe)
            );
        } catch (RuntimeException exception) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_REASON_CODE_NOT_ALLOWED);
        }
        return safe;
    }

    private <T extends Enum<T>> T boundedEnum(T value, Class<T> enumType) {
        requireShape(value);
        boundedString(value.toString());
        if (!List.of(enumType.getEnumConstants()).contains(value)) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
        return value;
    }

    private <T extends Enum<T>> T boundedOptionalEnum(T value, Class<T> enumType) {
        if (value == null) {
            return null;
        }
        return boundedEnum(value, enumType);
    }

    private String boundedString(String value) {
        if (value == null || value.isBlank()
                || value.chars().anyMatch(Character::isISOControl)
                || containsForbiddenText(value)) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
        if (value.length() > MAX_STRING_LENGTH) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
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
            Function<T, R> mapper
    ) {
        requireShape(source);
        if (source.size() > maximum) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_OVERSIZED);
        }
        return source.stream().map(mapper).toList();
    }

    private <T> T publicContract(Supplier<T> factory) {
        try {
            return factory.get();
        } catch (EngineIntelligenceProjectionValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
    }

    private void requireShape(Object value) {
        if (value == null) {
            throw validation(EngineIntelligenceProjectionOmissionReason.ENGINE_INTELLIGENCE_INVALID_SHAPE);
        }
    }

    private EngineIntelligenceProjectionValidationException validation(
            EngineIntelligenceProjectionOmissionReason reason
    ) {
        return new EngineIntelligenceProjectionValidationException(reason);
    }
}
