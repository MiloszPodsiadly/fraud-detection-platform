package com.frauddetection.alert.service;

import com.frauddetection.alert.api.EvidenceSourceCountResponse;
import com.frauddetection.alert.api.EvidenceStatusCountResponse;
import com.frauddetection.alert.api.EvidenceSummaryItemResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.evidence.EvidenceSeverity;
import com.frauddetection.alert.evidence.EvidenceSnapshotItem;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.fraudcase.FraudCaseNotFoundException;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FraudCaseEvidenceSummaryService {

    static final int MAX_LINKED_ALERTS_FOR_EVIDENCE_SUMMARY = 100;
    static final int MAX_TOP_REASON_CODES = 10;
    static final int MAX_HIGHEST_SEVERITY_EVIDENCE = 10;
    static final String LINKED_ALERT_LIMIT_EXCEEDED = "LINKED_ALERT_LIMIT_EXCEEDED";

    private final FraudCaseRepository fraudCaseRepository;
    private final AlertRepository alertRepository;
    private final Clock clock;

    @Autowired
    public FraudCaseEvidenceSummaryService(FraudCaseRepository fraudCaseRepository, AlertRepository alertRepository) {
        this(fraudCaseRepository, alertRepository, Clock.systemUTC());
    }

    FraudCaseEvidenceSummaryService(FraudCaseRepository fraudCaseRepository, AlertRepository alertRepository, Clock clock) {
        this.fraudCaseRepository = fraudCaseRepository;
        this.alertRepository = alertRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public FraudCaseEvidenceSummaryResponse summary(String caseId) {
        FraudCaseDocument fraudCase = fraudCaseRepository.findById(caseId)
                .orElseThrow(() -> new FraudCaseNotFoundException(caseId));
        List<String> linkedAlertIds = normalizedLinkedAlertIds(fraudCase);
        boolean legacy = linkedAlertIds.isEmpty();
        boolean truncated = linkedAlertIds.size() > MAX_LINKED_ALERTS_FOR_EVIDENCE_SUMMARY;
        List<String> includedAlertIds = truncated
                ? linkedAlertIds.subList(0, MAX_LINKED_ALERTS_FOR_EVIDENCE_SUMMARY)
                : linkedAlertIds;
        EvidenceReadResult readResult = evidenceItems(includedAlertIds);
        List<EvidenceSnapshotItem> evidenceItems = readResult.items();
        boolean missingLinkedAlerts = includedAlertIds.size() > readResult.alertCount();
        boolean incompleteSourceCoverage = truncated || missingLinkedAlerts;
        boolean partial = incompleteSourceCoverage || containsPartialStatus(evidenceItems);
        EvidenceStatus aggregateStatus = aggregateStatus(evidenceItems, legacy, incompleteSourceCoverage);
        if (aggregateStatus == EvidenceStatus.PARTIAL) {
            partial = true;
        }
        return new FraudCaseEvidenceSummaryResponse(
                fraudCase.getCaseId(),
                aggregateStatus,
                topReasonCodes(evidenceItems),
                highestSeverityEvidence(evidenceItems),
                evidenceBySource(evidenceItems),
                evidenceByStatus(evidenceItems),
                linkedAlertIds.size(),
                evidenceItems.size(),
                partial,
                legacy,
                truncated,
                truncated ? LINKED_ALERT_LIMIT_EXCEEDED : null,
                Instant.now(clock)
        );
    }

    private EvidenceReadResult evidenceItems(List<String> alertIds) {
        Map<String, AlertDocument> alertsById = alertsById(alertIds);
        List<EvidenceSnapshotItem> items = new ArrayList<>();
        for (String alertId : alertIds) {
            AlertDocument alert = alertsById.get(alertId);
            if (alert != null) {
                List<EvidenceSnapshotItem> snapshot = alert.getEvidenceSnapshot();
                if (snapshot != null) {
                    items.addAll(snapshot);
                }
            }
        }
        return new EvidenceReadResult(List.copyOf(items), alertsById.size());
    }

    private Map<String, AlertDocument> alertsById(List<String> alertIds) {
        if (alertIds.isEmpty()) {
            return Map.of();
        }
        return alertRepository.findAllById(alertIds).stream()
                .filter(alert -> StringUtils.hasText(alert.getAlertId()))
                .collect(Collectors.toUnmodifiableMap(AlertDocument::getAlertId, Function.identity(), (left, right) -> left));
    }

    private List<String> normalizedLinkedAlertIds(FraudCaseDocument fraudCase) {
        if (fraudCase.getLinkedAlertIds() == null) {
            return List.of();
        }
        return fraudCase.getLinkedAlertIds().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private EvidenceStatus aggregateStatus(
            List<EvidenceSnapshotItem> evidenceItems,
            boolean legacy,
            boolean incompleteSourceCoverage
    ) {
        if (evidenceItems.isEmpty()) {
            if (legacy) {
                return EvidenceStatus.LEGACY;
            }
            return incompleteSourceCoverage ? EvidenceStatus.PARTIAL : EvidenceStatus.UNAVAILABLE;
        }
        if (evidenceItems.stream().anyMatch(item -> item.status() == EvidenceStatus.ERROR)) {
            return EvidenceStatus.ERROR;
        }
        if (incompleteSourceCoverage || containsPartialStatus(evidenceItems)) {
            return EvidenceStatus.PARTIAL;
        }
        boolean allAvailable = evidenceItems.stream().allMatch(item -> item.status() == EvidenceStatus.AVAILABLE);
        return allAvailable ? EvidenceStatus.AVAILABLE : EvidenceStatus.UNAVAILABLE;
    }

    private boolean containsPartialStatus(List<EvidenceSnapshotItem> evidenceItems) {
        return evidenceItems.stream().anyMatch(item -> item.status() == EvidenceStatus.PARTIAL
                || item.status() == EvidenceStatus.LEGACY
                || item.status() == EvidenceStatus.STALE
                || item.status() == EvidenceStatus.UNAVAILABLE
                || item.status() == EvidenceStatus.NOT_APPLICABLE);
    }

    private List<String> topReasonCodes(List<EvidenceSnapshotItem> evidenceItems) {
        Map<String, Long> counts = evidenceItems.stream()
                .map(EvidenceSnapshotItem::reasonCode)
                .filter(this::isSupportedReasonCode)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
                .limit(MAX_TOP_REASON_CODES)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<EvidenceSummaryItemResponse> highestSeverityEvidence(List<EvidenceSnapshotItem> evidenceItems) {
        return evidenceItems.stream()
                .sorted(evidenceItemComparator())
                .limit(MAX_HIGHEST_SEVERITY_EVIDENCE)
                .map(item -> new EvidenceSummaryItemResponse(
                        isSupportedReasonCode(item.reasonCode()) ? item.reasonCode().trim() : null,
                        item.evidenceType(),
                        item.severity(),
                        item.source(),
                        item.status(),
                        boundedTitle(item),
                        boundedDescription()
                ))
                .toList();
    }

    private String boundedTitle(EvidenceSnapshotItem item) {
        if (item == null || item.evidenceType() == null) {
            return "Evidence summary item";
        }
        return switch (item.evidenceType()) {
            case TRANSACTION_FEATURE -> "Transaction feature evidence";
            case CUSTOMER_BEHAVIOR -> "Customer behavior evidence";
            case DEVICE_SIGNAL -> "Device evidence";
            case GEO_SIGNAL -> "Location evidence";
            case VELOCITY_SIGNAL -> "Velocity evidence";
            case MERCHANT_SIGNAL -> "Merchant evidence";
            case RULE_MATCH -> "Rule evidence";
            case MODEL_EXPLANATION -> "Model explanation evidence";
            case SCORING_SNAPSHOT -> "Scoring snapshot evidence";
            case DIAGNOSTIC -> "Diagnostic evidence";
        };
    }

    private String boundedDescription() {
        return "Bounded evidence metadata derived from the linked alert evidence snapshot.";
    }

    private List<EvidenceSourceCountResponse> evidenceBySource(List<EvidenceSnapshotItem> evidenceItems) {
        Map<EvidenceSource, Long> counts = new EnumMap<>(EvidenceSource.class);
        evidenceItems.forEach(item -> counts.merge(item.source(), 1L, Long::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new EvidenceSourceCountResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<EvidenceStatusCountResponse> evidenceByStatus(List<EvidenceSnapshotItem> evidenceItems) {
        Map<EvidenceStatus, Long> counts = new EnumMap<>(EvidenceStatus.class);
        evidenceItems.forEach(item -> counts.merge(item.status(), 1L, Long::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new EvidenceStatusCountResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Comparator<EvidenceSnapshotItem> evidenceItemComparator() {
        return Comparator
                .comparingInt((EvidenceSnapshotItem item) -> severityRank(item.severity()))
                .thenComparing(item -> item.reasonCode() == null ? "" : item.reasonCode())
                .thenComparing(item -> item.evidenceType().name())
                .thenComparing(item -> item.source().name())
                .thenComparing(item -> item.status().name())
                .thenComparing(this::boundedTitle)
                .thenComparing(item -> boundedDescription());
    }

    private int severityRank(EvidenceSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }

    private boolean isSupportedReasonCode(String reasonCode) {
        return StringUtils.hasText(reasonCode)
                && !"UNKNOWN".equals(reasonCode.trim().toUpperCase(Locale.ROOT));
    }

    private record EvidenceReadResult(List<EvidenceSnapshotItem> items, int alertCount) {
    }
}
