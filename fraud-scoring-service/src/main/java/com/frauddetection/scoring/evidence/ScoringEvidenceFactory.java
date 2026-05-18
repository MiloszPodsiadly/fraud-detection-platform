package com.frauddetection.scoring.evidence;

import com.frauddetection.common.events.evidence.ScoringEvidenceAttributes;
import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.events.reason.ReasonCodeParseResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ScoringEvidenceFactory {

    private final ReasonCodeScoringEvidenceTypeMapper typeMapper;

    public ScoringEvidenceFactory() {
        this(new ReasonCodeScoringEvidenceTypeMapper());
    }

    ScoringEvidenceFactory(ReasonCodeScoringEvidenceTypeMapper typeMapper) {
        this.typeMapper = typeMapper;
    }

    public Optional<ScoringEvidenceItem> supported(
            ReasonCode reasonCode,
            ScoringEvidenceSource source,
            RiskLevel riskLevel,
            Instant observedAt,
            int index,
            Map<String, Object> attributes
    ) {
        Optional<ScoringEvidenceType> evidenceType = typeMapper.map(reasonCode);
        if (reasonCode == null || reasonCode == ReasonCode.UNKNOWN || evidenceType.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ScoringEvidenceItem(
                evidenceId(source, reasonCode.wireValue(), index),
                reasonCode.wireValue(),
                evidenceType.get(),
                source,
                ScoringEvidenceStatus.AVAILABLE,
                severity(riskLevel),
                reasonCode.title(),
                reasonCode.description(),
                null,
                null,
                ScoringEvidenceAttributes.safeCopy(attributes),
                observedAt
        ));
    }

    public List<ScoringEvidenceItem> supportedReasonCodes(
            Collection<String> reasonCodes,
            ScoringEvidenceSource source,
            RiskLevel riskLevel,
            Instant observedAt
    ) {
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            return List.of();
        }
        List<ScoringEvidenceItem> evidence = new ArrayList<>();
        int index = 0;
        for (String reasonCodeValue : reasonCodes) {
            Optional<ReasonCode> reasonCode = ReasonCode.known(reasonCodeValue);
            if (reasonCode.isPresent()) {
                supported(reasonCode.get(), source, riskLevel, observedAt, index, Map.of())
                        .ifPresent(evidence::add);
                index++;
            }
        }
        return List.copyOf(evidence);
    }

    public List<ScoringEvidenceItem> modelEvidence(
            List<ReasonCodeParseResult> parsedReasonCodes,
            boolean modelAvailable,
            RiskLevel riskLevel,
            Instant observedAt,
            String fallbackReason
    ) {
        parsedReasonCodes = parsedReasonCodes == null ? List.of() : parsedReasonCodes;
        List<ScoringEvidenceItem> evidence = new ArrayList<>();
        if (!modelAvailable) {
            evidence.add(modelUnavailable(fallbackReason, observedAt));
            return List.copyOf(evidence);
        }

        int index = 0;
        for (ReasonCodeParseResult parsedReasonCode : parsedReasonCodes) {
            if (parsedReasonCode != null
                    && parsedReasonCode.supported()
                    && parsedReasonCode.reasonCode() != ReasonCode.UNKNOWN) {
                supported(
                        parsedReasonCode.reasonCode(),
                        ScoringEvidenceSource.ML_MODEL,
                        riskLevel,
                        observedAt,
                        index,
                        Map.of("parseStatus", parsedReasonCode.status().name())
                ).ifPresent(evidence::add);
                index++;
            }
        }

        int unsupportedCount = unsupportedReasonCodeCount(parsedReasonCodes);
        if (unsupportedCount > 0) {
            evidence.add(unsupportedReasonCodeDiagnostic(parsedReasonCodes, ScoringEvidenceSource.ML_MODEL, observedAt, index));
        }
        if (isHighOrCritical(riskLevel) && evidence.stream().noneMatch(item -> item.status() == ScoringEvidenceStatus.AVAILABLE)) {
            evidence.add(missingSupportedReasonCodes(ScoringEvidenceSource.ML_MODEL, riskLevel, observedAt, index + 1));
        }
        return List.copyOf(evidence);
    }

    public ScoringEvidenceItem missingSupportedReasonCodes(
            ScoringEvidenceSource source,
            RiskLevel riskLevel,
            Instant observedAt,
            int index
    ) {
        return diagnostic(
                source,
                ScoringEvidenceStatus.PARTIAL,
                severity(riskLevel),
                "missing_supported_reason_codes",
                "Scoring context did not produce supported reason codes for the risk level.",
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false,
                        "diagnosticIndex", index,
                        "scoringEvidenceState", "missing_supported_reason_codes"
                ),
                observedAt,
                index
        );
    }

    public ScoringEvidenceItem unsupportedReasonCodeDiagnostic(
            List<ReasonCodeParseResult> parsedReasonCodes,
            ScoringEvidenceSource source,
            Instant observedAt,
            int index
    ) {
        parsedReasonCodes = parsedReasonCodes == null ? List.of() : parsedReasonCodes;
        int count = unsupportedReasonCodeCount(parsedReasonCodes);
        int maxLength = maxUnsupportedReasonCodeLength(parsedReasonCodes);
        return diagnostic(
                source,
                ScoringEvidenceStatus.PARTIAL,
                ScoringEvidenceSeverity.LOW,
                "unsupported_reason_code",
                "Unsupported model reason-code input was excluded from supported scoring evidence.",
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false,
                        "diagnosticIndex", index,
                        "unsupportedReasonCodePresent", count > 0,
                        "unsupportedReasonCodeCount", count,
                        "unsupportedReasonCodeLength", maxLength,
                        "parseStatus", parseStatusSummary(parsedReasonCodes),
                        "scoringEvidenceState", "unsupported_reason_code_excluded"
                ),
                observedAt,
                index
        );
    }

    public ScoringEvidenceItem modelUnavailable(String fallbackReason, Instant observedAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("diagnostic", true);
        attributes.put("supportedEvidenceCreated", false);
        attributes.put("reasonCodeApplicable", true);
        attributes.put("modelAvailable", false);
        attributes.put("scoringEvidenceState", "ml_model_unavailable");
        attributes.put("fallbackReasonCode", fallbackReasonCode(fallbackReason));
        attributes.put("fallbackReasonLength", fallbackReason == null ? 0 : fallbackReason.length());
        attributes.put("fallbackReasonProvided", fallbackReason != null && !fallbackReason.isBlank());
        return new ScoringEvidenceItem(
                evidenceId(ScoringEvidenceSource.ML_RUNTIME, ReasonCode.ML_MODEL_UNAVAILABLE.wireValue(), 0),
                ReasonCode.ML_MODEL_UNAVAILABLE.wireValue(),
                ScoringEvidenceType.MODEL_EXPLANATION,
                ScoringEvidenceSource.ML_RUNTIME,
                ScoringEvidenceStatus.UNAVAILABLE,
                ScoringEvidenceSeverity.LOW,
                ReasonCode.ML_MODEL_UNAVAILABLE.title(),
                ReasonCode.ML_MODEL_UNAVAILABLE.description(),
                null,
                null,
                ScoringEvidenceAttributes.safeCopy(attributes),
                observedAt
        );
    }

    public ScoringEvidenceItem decisionFallbackDiagnostic(Instant observedAt, int index) {
        return diagnostic(
                ScoringEvidenceSource.SCORING_FALLBACK,
                ScoringEvidenceStatus.LEGACY,
                ScoringEvidenceSeverity.LOW,
                "ml_decision_fallback_used",
                "Fallback scoring path was used and recorded as diagnostic scoring evidence.",
                Map.of(
                        "diagnostic", true,
                        "fallbackUsed", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false,
                        "diagnosticIndex", index,
                        "scoringEvidenceState", "ml_decision_fallback_used"
                ),
                observedAt,
                index
        );
    }

    public ScoringEvidenceItem mlRuntimeDiagnostic(String state, Instant observedAt, int index) {
        String safeState = state == null || state.isBlank() ? "missing-code" : state;
        return diagnostic(
                ScoringEvidenceSource.ML_RUNTIME,
                ScoringEvidenceStatus.UNAVAILABLE,
                ScoringEvidenceSeverity.LOW,
                safeState,
                "ML runtime context was unavailable for non-decision scoring diagnostics.",
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false,
                        "diagnosticIndex", index,
                        "scoringEvidenceState", safeState
                ),
                observedAt,
                index
        );
    }

    private ScoringEvidenceItem diagnostic(
            ScoringEvidenceSource source,
            ScoringEvidenceStatus status,
            ScoringEvidenceSeverity severity,
            String state,
            String description,
            Map<String, Object> attributes,
            Instant observedAt,
            int index
    ) {
        String safeState = state == null || state.isBlank() ? "missing-code" : state;
        Map<String, Object> safeAttributes = new LinkedHashMap<>(attributes);
        safeAttributes.put("scoringEvidenceState", safeState);
        return new ScoringEvidenceItem(
                evidenceId(source, safeState, index),
                null,
                ScoringEvidenceType.DIAGNOSTIC,
                source,
                status,
                severity,
                "Scoring evidence diagnostic",
                description,
                null,
                null,
                ScoringEvidenceAttributes.safeCopy(safeAttributes),
                observedAt
        );
    }

    private ScoringEvidenceSeverity severity(RiskLevel riskLevel) {
        if (riskLevel == RiskLevel.CRITICAL) {
            return ScoringEvidenceSeverity.CRITICAL;
        }
        if (riskLevel == RiskLevel.HIGH) {
            return ScoringEvidenceSeverity.HIGH;
        }
        if (riskLevel == RiskLevel.MEDIUM) {
            return ScoringEvidenceSeverity.MEDIUM;
        }
        return ScoringEvidenceSeverity.LOW;
    }

    private boolean isHighOrCritical(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private int unsupportedReasonCodeCount(List<ReasonCodeParseResult> parsedReasonCodes) {
        int count = 0;
        for (ReasonCodeParseResult parsedReasonCode : parsedReasonCodes) {
            if (parsedReasonCode != null && !parsedReasonCode.supported()) {
                count++;
            }
        }
        return count;
    }

    private int maxUnsupportedReasonCodeLength(List<ReasonCodeParseResult> parsedReasonCodes) {
        int maxLength = 0;
        for (ReasonCodeParseResult parsedReasonCode : parsedReasonCodes) {
            if (parsedReasonCode != null && !parsedReasonCode.supported() && parsedReasonCode.rawValue() != null) {
                maxLength = Math.max(maxLength, parsedReasonCode.rawValue().length());
            }
        }
        return maxLength;
    }

    private String parseStatusSummary(List<ReasonCodeParseResult> parsedReasonCodes) {
        List<String> statuses = new ArrayList<>();
        for (ReasonCodeParseResult parsedReasonCode : parsedReasonCodes) {
            if (parsedReasonCode != null && !parsedReasonCode.supported()) {
                statuses.add(parsedReasonCode.status().name());
            }
        }
        if (statuses.isEmpty()) {
            return "NONE";
        }
        return statuses.stream().distinct().count() == 1 ? statuses.getFirst() : "MIXED";
    }

    private String evidenceId(ScoringEvidenceSource source, String code, int index) {
        String safeSource = source == null ? "UNKNOWN_SOURCE" : source.name();
        String safeCode = code == null || code.isBlank() ? "missing-code" : code.trim();
        return safeSource + ":" + safeCode.toLowerCase(Locale.ROOT).replace(' ', '_') + ":" + index;
    }

    private String fallbackReasonCode(String fallbackReason) {
        if (fallbackReason == null || fallbackReason.isBlank()) {
            return "unknown_fallback_reason";
        }
        String normalized = fallbackReason.toLowerCase(Locale.ROOT);
        if (normalized.contains("request failed")) {
            return "ml_request_failed";
        }
        if (normalized.contains("invalid") || normalized.contains("empty response")) {
            return "ml_response_invalid";
        }
        if (normalized.contains("runtime")) {
            return "ml_runtime_unavailable";
        }
        if (normalized.contains("unavailable") || normalized.contains("not configured")) {
            return "ml_model_unavailable";
        }
        if (normalized.contains("fallback")) {
            return "scoring_fallback_used";
        }
        return "unknown_fallback_reason";
    }
}
