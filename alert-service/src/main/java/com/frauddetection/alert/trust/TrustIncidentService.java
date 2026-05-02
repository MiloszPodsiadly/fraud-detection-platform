package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentAcknowledgeMutationHandler;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentRefreshMutationHandler;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentResolveMutationHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TrustIncidentService {

    private final TrustIncidentRepository repository;
    private final TrustIncidentPolicy policy;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final TrustIncidentAcknowledgeMutationHandler acknowledgeMutationHandler;
    private final TrustIncidentResolveMutationHandler resolveMutationHandler;
    private final TrustIncidentRefreshMutationHandler refreshMutationHandler;

    public TrustIncidentService(
            TrustIncidentRepository repository,
            TrustIncidentPolicy policy,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            TrustIncidentAcknowledgeMutationHandler acknowledgeMutationHandler,
            TrustIncidentResolveMutationHandler resolveMutationHandler,
            TrustIncidentRefreshMutationHandler refreshMutationHandler
    ) {
        this.repository = repository;
        this.policy = policy;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.acknowledgeMutationHandler = acknowledgeMutationHandler;
        this.resolveMutationHandler = resolveMutationHandler;
        this.refreshMutationHandler = refreshMutationHandler;
    }

    public List<TrustIncidentResponse> listOpen() {
        return repository.findTop100ByStatusInOrderByUpdatedAtDesc(policy.openStatuses()).stream()
                .map(TrustIncidentResponse::from)
                .toList();
    }

    public TrustIncidentResponse acknowledge(
            String incidentId,
            TrustIncidentAcknowledgementRequest request,
            String actorId,
            String idempotencyKey
    ) {
        TrustIncidentAcknowledgementRequest normalized = request == null
                ? new TrustIncidentAcknowledgementRequest(null)
                : request;
        String requestHash = RegulatedMutationIntentHasher.hash("incidentId=" + RegulatedMutationIntentHasher.canonicalValue(incidentId)
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(normalized.reason()));
        RegulatedMutationCommand<TrustIncidentDocument, TrustIncidentResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                incidentId,
                AuditResourceType.TRUST_INCIDENT,
                AuditAction.ACK_TRUST_INCIDENT,
                null,
                requestHash,
                context -> acknowledgeMutationHandler.acknowledge(incidentId, normalized, actorId),
                (saved, state) -> TrustIncidentResponse.from(saved),
                RegulatedMutationResponseSnapshot::fromTrustIncident,
                RegulatedMutationResponseSnapshot::toTrustIncidentResponse,
                state -> statusResponse(incidentId, state),
                intent(incidentId, AuditAction.ACK_TRUST_INCIDENT, actorId, TrustIncidentStatus.ACKNOWLEDGED, normalized.reason())
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    public TrustIncidentResponse resolve(
            String incidentId,
            TrustIncidentResolutionRequest request,
            String actorId,
            String idempotencyKey
    ) {
        ResolutionEvidenceReference.require(request.resolutionEvidence(), "resolution evidence is required");
        TrustIncidentStatus targetStatus = request.falsePositive() ? TrustIncidentStatus.FALSE_POSITIVE : TrustIncidentStatus.RESOLVED;
        String requestHash = RegulatedMutationIntentHasher.hash("incidentId=" + RegulatedMutationIntentHasher.canonicalValue(incidentId)
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|targetStatus=" + targetStatus.name()
                + "|reason=" + RegulatedMutationIntentHasher.canonicalValue(request.reason())
                + "|evidence=" + RegulatedMutationIntentHasher.canonicalValue(request.resolutionEvidence()));
        RegulatedMutationCommand<TrustIncidentDocument, TrustIncidentResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                incidentId,
                AuditResourceType.TRUST_INCIDENT,
                AuditAction.RESOLVE_TRUST_INCIDENT,
                null,
                requestHash,
                context -> resolveMutationHandler.resolve(incidentId, request, actorId),
                (saved, state) -> TrustIncidentResponse.from(saved),
                RegulatedMutationResponseSnapshot::fromTrustIncident,
                RegulatedMutationResponseSnapshot::toTrustIncidentResponse,
                state -> statusResponse(incidentId, state),
                intent(incidentId, AuditAction.RESOLVE_TRUST_INCIDENT, actorId, targetStatus, request.reason())
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    public TrustIncidentMaterializationResponse refresh(List<TrustSignal> signals, String actorId, String idempotencyKey) {
        List<TrustSignal> safeSignals = signals == null ? List.of() : List.copyOf(signals);
        String signalHash = signalHash(safeSignals);
        String requestHash = RegulatedMutationIntentHasher.hash("actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|signalHash=" + signalHash);
        RegulatedMutationCommand<TrustIncidentMaterializationResponse, TrustIncidentMaterializationResponse> command = new RegulatedMutationCommand<>(
                idempotencyKey,
                actorId,
                "trust-incidents",
                AuditResourceType.TRUST_INCIDENT,
                AuditAction.REFRESH_TRUST_INCIDENTS,
                null,
                requestHash,
                context -> refreshMutationHandler.refresh(safeSignals),
                (response, state) -> response,
                RegulatedMutationResponseSnapshot::fromTrustIncidentMaterialization,
                RegulatedMutationResponseSnapshot::toTrustIncidentMaterializationResponse,
                state -> materializationStatusResponse(safeSignals.size(), state),
                refreshIntent(actorId, signalHash)
        );
        return regulatedMutationCoordinator.commit(command).response();
    }

    public TrustIncidentSummary summary() {
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

    private RegulatedMutationIntent intent(
            String incidentId,
            AuditAction action,
            String actorId,
            TrustIncidentStatus targetStatus,
            String reason
    ) {
        String status = targetStatus.name();
        String reasonHash = RegulatedMutationIntentHasher.hash(reason);
        String intentHash = RegulatedMutationIntentHasher.hash("resourceId=" + RegulatedMutationIntentHasher.canonicalValue(incidentId)
                + "|action=" + action.name()
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|status=" + status
                + "|reasonHash=" + reasonHash);
        return new RegulatedMutationIntent(
                intentHash,
                incidentId,
                action.name(),
                actorId,
                null,
                reasonHash,
                null,
                status,
                null,
                reasonHash,
                null
        );
    }

    private RegulatedMutationIntent refreshIntent(String actorId, String signalHash) {
        String intentHash = RegulatedMutationIntentHasher.hash("resourceId=trust-incidents"
                + "|action=" + AuditAction.REFRESH_TRUST_INCIDENTS.name()
                + "|actorId=" + RegulatedMutationIntentHasher.canonicalValue(actorId)
                + "|signalHash=" + signalHash);
        return new RegulatedMutationIntent(
                intentHash,
                "trust-incidents",
                AuditAction.REFRESH_TRUST_INCIDENTS.name(),
                actorId,
                null,
                null,
                null,
                null,
                null,
                null,
                signalHash
        );
    }

    private String signalHash(List<TrustSignal> signals) {
        String canonicalSignals = signals.stream()
                .map(this::canonicalSignal)
                .sorted()
                .collect(Collectors.joining(";"));
        return RegulatedMutationIntentHasher.hash(canonicalSignals);
    }

    private String canonicalSignal(TrustSignal signal) {
        String evidenceRefs = signal.evidenceRefs() == null ? "" : signal.evidenceRefs().stream()
                .sorted()
                .collect(Collectors.joining(","));
        return "type=" + RegulatedMutationIntentHasher.canonicalValue(signal.type())
                + "|severity=" + (signal.severity() == null ? "" : signal.severity().name())
                + "|source=" + RegulatedMutationIntentHasher.canonicalValue(signal.source())
                + "|fingerprint=" + RegulatedMutationIntentHasher.canonicalValue(signal.fingerprint())
                + "|evidenceRefs=" + RegulatedMutationIntentHasher.canonicalValue(evidenceRefs);
    }

    private TrustIncidentMaterializationResponse materializationStatusResponse(int signalCount, RegulatedMutationState state) {
        return new TrustIncidentMaterializationResponse(
                state.name(),
                signalCount,
                0,
                signalCount,
                0,
                0,
                false,
                state == RegulatedMutationState.COMMITTED_DEGRADED || state == RegulatedMutationState.FAILED ? state.name() : null,
                List.of(),
                state.name(),
                null,
                false,
                state == RegulatedMutationState.COMMITTED_DEGRADED || state == RegulatedMutationState.FAILED ? state.name() : null
        );
    }

    private TrustIncidentResponse statusResponse(String incidentId, RegulatedMutationState state) {
        if (state == RegulatedMutationState.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "trust incident mutation rejected before state change");
        }
        return new TrustIncidentResponse(
                incidentId,
                null,
                null,
                null,
                null,
                state.name(),
                null,
                null,
                0L,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
