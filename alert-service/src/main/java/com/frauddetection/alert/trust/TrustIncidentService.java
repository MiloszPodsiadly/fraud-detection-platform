package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TrustIncidentService {

    private final TrustIncidentRepository repository;
    private final TrustIncidentPolicy policy;
    private final AuditService auditService;

    public TrustIncidentService(TrustIncidentRepository repository, TrustIncidentPolicy policy, AuditService auditService) {
        this.repository = repository;
        this.policy = policy;
        this.auditService = auditService;
    }

    public List<TrustIncidentResponse> listOpen(List<TrustSignal> signals) {
        refreshSignals(signals);
        return repository.findTop100ByStatusInOrderByUpdatedAtDesc(policy.openStatuses()).stream()
                .map(TrustIncidentResponse::from)
                .toList();
    }

    public TrustIncidentResponse acknowledge(String incidentId, TrustIncidentAcknowledgementRequest request, String actorId) {
        TrustIncidentDocument incident = load(incidentId);
        if (!policy.openStatuses().contains(incident.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "trust incident is not open");
        }
        auditService.audit(
                AuditAction.ACK_TRUST_INCIDENT,
                AuditResourceType.TRUST_INCIDENT,
                incidentId,
                null,
                actorId,
                AuditOutcome.SUCCESS,
                null
        );
        Instant now = Instant.now();
        incident.setStatus(TrustIncidentStatus.ACKNOWLEDGED);
        incident.setAcknowledgedBy(actorId);
        incident.setAcknowledgedAt(now);
        incident.setUpdatedAt(now);
        return TrustIncidentResponse.from(repository.save(incident));
    }

    public TrustIncidentResponse resolve(String incidentId, TrustIncidentResolutionRequest request, String actorId) {
        TrustIncidentDocument incident = load(incidentId);
        if (!policy.openStatuses().contains(incident.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "trust incident is not open");
        }
        ResolutionEvidenceReference.require(request.resolutionEvidence(), "resolution evidence is required");
        auditService.audit(
                AuditAction.RESOLVE_TRUST_INCIDENT,
                AuditResourceType.TRUST_INCIDENT,
                incidentId,
                null,
                actorId,
                AuditOutcome.SUCCESS,
                null
        );
        Instant now = Instant.now();
        incident.setStatus(request.falsePositive() ? TrustIncidentStatus.FALSE_POSITIVE : TrustIncidentStatus.RESOLVED);
        incident.setResolvedBy(actorId);
        incident.setResolvedAt(now);
        incident.setResolutionReason(request.reason());
        incident.setResolutionEvidence(request.resolutionEvidence());
        incident.setUpdatedAt(now);
        return TrustIncidentResponse.from(repository.save(incident));
    }

    public TrustIncidentSummary summary(List<TrustSignal> signals) {
        refreshSignals(signals);
        long critical = repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.CRITICAL);
        long high = repository.countByStatusInAndSeverity(policy.openStatuses(), TrustIncidentSeverity.HIGH);
        long unacknowledgedCritical = repository.countByStatusInAndSeverityAndAcknowledgedAtIsNull(
                policy.openStatuses(),
                TrustIncidentSeverity.CRITICAL
        );
        Instant now = Instant.now();
        Long oldest = repository.findTopByStatusInOrderByFirstSeenAtAsc(policy.openStatuses())
                .map(TrustIncidentDocument::getFirstSeenAt)
                .map(firstSeen -> Math.max(0L, Duration.between(firstSeen, now).toSeconds()))
                .orElse(null);
        List<String> topTypes = repository.findTop100ByStatusInOrderByUpdatedAtDesc(policy.openStatuses()).stream()
                .collect(LinkedHashMap<String, Long>::new, (counts, incident) -> counts.merge(incident.getType(), 1L, Long::sum), Map::putAll)
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
        TrustIncidentSummary base = new TrustIncidentSummary(critical, high, unacknowledgedCritical, oldest, topTypes, "HEALTHY");
        return new TrustIncidentSummary(
                base.openCriticalIncidentCount(),
                base.openHighIncidentCount(),
                base.unacknowledgedCriticalIncidentCount(),
                base.oldestOpenIncidentAgeSeconds(),
                base.topIncidentTypes(),
                policy.healthStatus(base)
        );
    }

    public void refreshSignals(List<TrustSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        signals.forEach(this::upsertSignal);
    }

    private void upsertSignal(TrustSignal signal) {
        String fingerprint = signal.fingerprint() == null || signal.fingerprint().isBlank()
                ? RegulatedMutationIntentHasher.hash(signal.type() + "|" + signal.source())
                : RegulatedMutationIntentHasher.hash(signal.fingerprint());
        Instant now = Instant.now();
        TrustIncidentDocument incident = repository.findFirstByTypeAndSourceAndFingerprintAndStatusInOrderByUpdatedAtDesc(
                        signal.type(),
                        signal.source(),
                        fingerprint,
                        policy.openStatuses()
                )
                .orElseGet(() -> newIncident(signal, fingerprint, now));
        incident.setLastSeenAt(now);
        incident.setOccurrenceCount(Math.max(0L, incident.getOccurrenceCount()) + 1L);
        incident.setEvidenceRefs(mergeEvidence(incident.getEvidenceRefs(), signal.evidenceRefs()));
        incident.setUpdatedAt(now);
        repository.save(incident);
    }

    private TrustIncidentDocument newIncident(TrustSignal signal, String fingerprint, Instant now) {
        TrustIncidentDocument document = new TrustIncidentDocument();
        document.setIncidentId(UUID.randomUUID().toString());
        document.setType(signal.type());
        document.setSeverity(signal.severity() == null ? policy.severity(signal.type()) : signal.severity());
        document.setSource(signal.source());
        document.setFingerprint(fingerprint);
        document.setStatus(TrustIncidentStatus.OPEN);
        document.setFirstSeenAt(now);
        document.setCreatedAt(now);
        document.setOccurrenceCount(0L);
        document.setEvidenceRefs(List.of());
        return document;
    }

    private List<String> mergeEvidence(List<String> current, List<String> added) {
        List<String> merged = new ArrayList<>(current == null ? List.of() : current);
        policy.boundedEvidenceRefs(added).forEach(ref -> {
            if (!merged.contains(ref) && merged.size() < 10) {
                merged.add(ref);
            }
        });
        return List.copyOf(merged);
    }

    private TrustIncidentDocument load(String incidentId) {
        if (incidentId == null || incidentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trust incident not found");
        }
        return repository.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "trust incident not found"));
    }
}
