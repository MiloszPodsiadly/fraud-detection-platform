package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationIntent;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.trustincident.TrustIncidentAcknowledgeMutationHandler;
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

@Service
public class TrustIncidentService {

    private final TrustIncidentRepository repository;
    private final TrustIncidentPolicy policy;
    private final RegulatedMutationCoordinator regulatedMutationCoordinator;
    private final TrustIncidentAcknowledgeMutationHandler acknowledgeMutationHandler;
    private final TrustIncidentResolveMutationHandler resolveMutationHandler;
    private final TrustIncidentMaterializer materializer;
    private final AuditService auditService;

    public TrustIncidentService(
            TrustIncidentRepository repository,
            TrustIncidentPolicy policy,
            RegulatedMutationCoordinator regulatedMutationCoordinator,
            TrustIncidentAcknowledgeMutationHandler acknowledgeMutationHandler,
            TrustIncidentResolveMutationHandler resolveMutationHandler,
            TrustIncidentMaterializer materializer,
            AuditService auditService
    ) {
        this.repository = repository;
        this.policy = policy;
        this.regulatedMutationCoordinator = regulatedMutationCoordinator;
        this.acknowledgeMutationHandler = acknowledgeMutationHandler;
        this.resolveMutationHandler = resolveMutationHandler;
        this.materializer = materializer;
        this.auditService = auditService;
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

    public TrustIncidentMaterializationResponse refresh(List<TrustSignal> signals, String actorId) {
        auditService.audit(
                AuditAction.REFRESH_TRUST_INCIDENTS,
                AuditResourceType.TRUST_INCIDENT,
                "trust-incidents",
                null,
                actorId,
                AuditOutcome.ATTEMPTED,
                null
        );
        TrustIncidentMaterializationResponse response = materializer.materialize(signals);
        auditService.audit(
                AuditAction.REFRESH_TRUST_INCIDENTS,
                AuditResourceType.TRUST_INCIDENT,
                "trust-incidents",
                null,
                actorId,
                AuditOutcome.SUCCESS,
                null
        );
        return response;
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
