package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.events.reason.ReasonCodeParseResult;
import com.frauddetection.common.events.reason.ReasonCodeParseStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EvidenceProjectionService {

    private final ReasonCodeEvidenceTypeMapper mapper;
    private final Clock clock;

    public EvidenceProjectionService() {
        this(new ReasonCodeEvidenceTypeMapper(), Clock.systemUTC());
    }

    EvidenceProjectionService(ReasonCodeEvidenceTypeMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<EvidenceDocument> projectFromScoredEvent(TransactionScoredEvent event) {
        Objects.requireNonNull(event, "event is required");
        EvidenceDocument linkageDiagnostic = linkageDiagnostic(event);
        if (linkageDiagnostic != null) {
            return List.of(linkageDiagnostic);
        }

        List<EvidenceDocument> evidence = new ArrayList<>();
        List<String> reasonCodes = event.reasonCodes();
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            if (isHighOrCritical(event.riskLevel())) {
                evidence.add(diagnosticEvidence(
                        event,
                        EvidenceStatus.PARTIAL,
                        "Scoring context missing supported reason codes",
                        "High-risk scoring context lacks supported reason codes for evidence projection.",
                        "missing_reason_codes",
                        Map.of("reasonCodeState", reasonCodes == null ? "null" : "empty")
                ));
            }
            return List.copyOf(evidence);
        }

        int index = 0;
        for (String rawReasonCode : reasonCodes) {
            ReasonCodeParseResult parsed = ReasonCode.parseLegacy(rawReasonCode);
            ReasonCode reasonCode = parsed.reasonCode();
            int currentIndex = index;
            if (parsed.supported() && reasonCode != ReasonCode.UNKNOWN) {
                mapper.mapSupported(reasonCode).ifPresent(evidenceType ->
                        evidence.add(supportedEvidence(event, parsed, evidenceType, currentIndex))
                );
            } else {
                evidence.add(unsupportedDiagnosticEvidence(event, parsed, currentIndex));
            }
            index++;
        }
        return List.copyOf(evidence);
    }

    private EvidenceDocument supportedEvidence(
            TransactionScoredEvent event,
            ReasonCodeParseResult parsed,
            EvidenceType evidenceType,
            int index
    ) {
        ReasonCode reasonCode = parsed.reasonCode();
        EvidenceDocument document = baseDocument(event, EvidenceSource.FRAUD_SCORING_SERVICE, EvidenceStatus.AVAILABLE);
        document.setEvidenceId(evidenceId(event, reasonCode.wireValue(), index));
        document.setReasonCode(reasonCode.wireValue());
        document.setEvidenceType(evidenceType);
        document.setSeverity(mapRiskLevelToEvidenceSeverity(event.riskLevel()));
        document.setTitle(reasonCode.title());
        document.setDescription(reasonCode.description());
        document.setValue(reasonCode.wireValue());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("reasonCodeParseStatus", parsed.status().name());
        attributes.put("evidenceSemantics", "investigation_context");
        if (hasText(event.eventId())) {
            attributes.put("sourceEventId", event.eventId());
        }
        document.setAttributes(attributes);
        return document;
    }

    private EvidenceDocument unsupportedDiagnosticEvidence(
            TransactionScoredEvent event,
            ReasonCodeParseResult parsed,
            int index
    ) {
        EvidenceStatus status = diagnosticStatus(parsed.status());
        return diagnosticEvidence(
                event,
                status,
                "Unsupported reason-code diagnostic",
                "Reason-code input could not be projected as supported evidence.",
                "unsupported_reason_code",
                Map.of(
                        "reasonCodeParseStatus", parsed.status().name(),
                        "unsupportedReasonCodePresent", parsed.rawValue() != null && !parsed.rawValue().isBlank(),
                        "supportedEvidenceCreated", false,
                        "diagnosticIndex", index
                )
        );
    }

    private EvidenceDocument diagnosticEvidence(
            TransactionScoredEvent event,
            EvidenceStatus status,
            String title,
            String description,
            String diagnosticCode,
            Map<String, Object> attributes
    ) {
        EvidenceDocument document = baseDocument(event, EvidenceSource.FRAUD_SCORING_SERVICE, status);
        document.setEvidenceId(evidenceId(event, diagnosticCode, attributes.getOrDefault("diagnosticIndex", 0)));
        document.setEvidenceType(EvidenceType.DIAGNOSTIC);
        document.setSeverity(mapRiskLevelToEvidenceSeverity(event.riskLevel()));
        document.setTitle(title);
        document.setDescription(description);
        document.setValue(diagnosticCode);
        Map<String, Object> mergedAttributes = new LinkedHashMap<>(attributes);
        mergedAttributes.put("diagnostic", true);
        mergedAttributes.put("sourceEventId", event.eventId());
        document.setAttributes(mergedAttributes);
        return document;
    }

    private EvidenceDocument baseDocument(TransactionScoredEvent event, EvidenceSource source, EvidenceStatus status) {
        EvidenceDocument document = EvidenceDocument.create(source, status);
        document.setTransactionId(event.transactionId());
        document.setCustomerId(event.customerId());
        document.setCorrelationId(event.correlationId());
        document.setSourceEventId(event.eventId());
        document.setEntityType(EvidenceEntityType.SCORED_TRANSACTION);
        document.setEntityId(event.transactionId());
        document.setCreatedAt(clock.instant());
        document.setObservedAt(observedAt(event));
        document.setScoringStrategy(event.scoringStrategy());
        document.setModelName(event.modelName());
        document.setModelVersion(event.modelVersion());
        return document;
    }

    private EvidenceStatus diagnosticStatus(ReasonCodeParseStatus status) {
        return switch (status) {
            case KNOWN, UNSUPPORTED -> EvidenceStatus.ERROR;
            case LEGACY_MAPPED -> EvidenceStatus.LEGACY;
            case BLANK, NULL_ITEM -> EvidenceStatus.UNAVAILABLE;
        };
    }

    private EvidenceSeverity mapRiskLevelToEvidenceSeverity(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return EvidenceSeverity.LOW;
        }
        return switch (riskLevel) {
            case LOW -> EvidenceSeverity.LOW;
            case MEDIUM -> EvidenceSeverity.MEDIUM;
            case HIGH -> EvidenceSeverity.HIGH;
            case CRITICAL -> EvidenceSeverity.CRITICAL;
        };
    }

    private Instant observedAt(TransactionScoredEvent event) {
        if (event.inferenceTimestamp() != null) {
            return event.inferenceTimestamp();
        }
        return event.createdAt();
    }

    private boolean isHighOrCritical(RiskLevel riskLevel) {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    private String evidenceId(TransactionScoredEvent event, Object code, Object index) {
        return "%s:%s:%s:%s:%s".formatted(
                safeId(event.correlationId(), "missing-correlation"),
                safeId(event.eventId(), "missing-event"),
                safeId(event.transactionId(), "missing-transaction"),
                safeId(String.valueOf(code), "missing-code"),
                safeId(String.valueOf(index), "missing-index")
        );
    }

    private EvidenceDocument linkageDiagnostic(TransactionScoredEvent event) {
        if (!hasText(event.transactionId())) {
            return diagnosticEvidence(
                    event,
                    EvidenceStatus.PARTIAL,
                    "Scoring context missing transaction id",
                    "Evidence projection could not create fully available evidence because transactionId is missing.",
                    "missing_transaction_id",
                    Map.of(
                            "missingTransactionId", true,
                            "transactionIdState", valueState(event.transactionId()),
                            "supportedEvidenceCreated", false,
                            "evidenceProjectionState", "missing_transaction_id"
                    )
            );
        }
        if (!hasText(event.correlationId())) {
            return diagnosticEvidence(
                    event,
                    EvidenceStatus.PARTIAL,
                    "Scoring context missing correlation id",
                    "Evidence projection could not create fully available evidence because correlationId is missing.",
                    "missing_correlation_id",
                    Map.of(
                            "missingCorrelationId", true,
                            "correlationIdState", valueState(event.correlationId()),
                            "supportedEvidenceCreated", false,
                            "evidenceProjectionState", "missing_correlation_id"
                    )
            );
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String valueState(String value) {
        return value == null ? "null" : "blank";
    }

    private String safeId(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
}
