package com.frauddetection.alert.regulated;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.api.UpdateFraudCaseResponse;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.outbox.OutboxRecordResponse;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.trust.TrustIncidentMaterializationResponse;
import com.frauddetection.alert.trust.TrustIncidentResponse;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;

import java.time.Instant;
import java.util.List;

public record RegulatedMutationResponseSnapshot(
        @JsonProperty("alert_id")
        String alertId,
        AnalystDecision decision,
        @JsonProperty("resulting_status")
        AlertStatus resultingStatus,
        @JsonProperty("decision_event_id")
        String decisionEventId,
        @JsonProperty("decided_at")
        Instant decidedAt,
        @JsonProperty("operation_status")
        SubmitDecisionOperationStatus operationStatus,
        @JsonProperty("outbox_alert_id")
        String outboxAlertId,
        @JsonProperty("outbox_event_id")
        String outboxEventId,
        @JsonProperty("outbox_dedupe_key")
        String outboxDedupeKey,
        @JsonProperty("outbox_status")
        String outboxStatus,
        @JsonProperty("outbox_decided_at")
        Instant outboxDecidedAt,
        @JsonProperty("outbox_attempts")
        Integer outboxAttempts,
        @JsonProperty("outbox_last_attempt_at")
        Instant outboxLastAttemptAt,
        @JsonProperty("outbox_published_at")
        Instant outboxPublishedAt,
        @JsonProperty("outbox_failure_reason")
        String outboxFailureReason,
        @JsonProperty("outbox_resolution_pending")
        Boolean outboxResolutionPending,
        @JsonProperty("outbox_resolution_requested_at")
        Instant outboxResolutionRequestedAt,
        @JsonProperty("outbox_resolution_requested_by")
        String outboxResolutionRequestedBy,
        @JsonProperty("outbox_resolution_evidence_type")
        String outboxResolutionEvidenceType,
        @JsonProperty("outbox_resolution_evidence_reference")
        String outboxResolutionEvidenceReference,
        @JsonProperty("outbox_resolution_evidence_verified_at")
        Instant outboxResolutionEvidenceVerifiedAt,
        @JsonProperty("outbox_resolution_evidence_verified_by")
        String outboxResolutionEvidenceVerifiedBy,
        @JsonProperty("outbox_resolution_approved_at")
        Instant outboxResolutionApprovedAt,
        @JsonProperty("outbox_resolution_approved_by")
        String outboxResolutionApprovedBy,
        @JsonProperty("outbox_resolution_approval_reason")
        String outboxResolutionApprovalReason,
        @JsonProperty("fraud_case_id")
        String fraudCaseId,
        @JsonProperty("fraud_case_status")
        FraudCaseStatus fraudCaseStatus,
        @JsonProperty("fraud_case_analyst_id")
        String fraudCaseAnalystId,
        @JsonProperty("fraud_case_decision_reason")
        String fraudCaseDecisionReason,
        @JsonProperty("fraud_case_decision_tags")
        List<String> fraudCaseDecisionTags,
        @JsonProperty("fraud_case_decided_at")
        Instant fraudCaseDecidedAt,
        @JsonProperty("fraud_case_updated_at")
        Instant fraudCaseUpdatedAt,
        @JsonProperty("trust_incident_id")
        String trustIncidentId,
        @JsonProperty("trust_incident_type")
        String trustIncidentType,
        @JsonProperty("trust_incident_severity")
        String trustIncidentSeverity,
        @JsonProperty("trust_incident_source")
        String trustIncidentSource,
        @JsonProperty("trust_incident_fingerprint")
        String trustIncidentFingerprint,
        @JsonProperty("trust_incident_status")
        String trustIncidentStatus,
        @JsonProperty("trust_incident_first_seen_at")
        Instant trustIncidentFirstSeenAt,
        @JsonProperty("trust_incident_last_seen_at")
        Instant trustIncidentLastSeenAt,
        @JsonProperty("trust_incident_occurrence_count")
        Long trustIncidentOccurrenceCount,
        @JsonProperty("trust_incident_evidence_refs")
        List<String> trustIncidentEvidenceRefs,
        @JsonProperty("trust_incident_acknowledged_by")
        String trustIncidentAcknowledgedBy,
        @JsonProperty("trust_incident_acknowledged_at")
        Instant trustIncidentAcknowledgedAt,
        @JsonProperty("trust_incident_acknowledgement_reason")
        String trustIncidentAcknowledgementReason,
        @JsonProperty("trust_incident_resolved_by")
        String trustIncidentResolvedBy,
        @JsonProperty("trust_incident_resolved_at")
        Instant trustIncidentResolvedAt,
        @JsonProperty("trust_incident_resolution_reason")
        String trustIncidentResolutionReason,
        @JsonProperty("trust_incident_resolution_evidence")
        ResolutionEvidenceReference trustIncidentResolutionEvidence,
        @JsonProperty("trust_incident_materialization")
        TrustIncidentMaterializationResponse trustIncidentMaterialization
) {
    private static final int ZERO = 0;

    public RegulatedMutationResponseSnapshot(
            String alertId,
            AnalystDecision decision,
            AlertStatus resultingStatus,
            String decisionEventId,
            Instant decidedAt,
            SubmitDecisionOperationStatus operationStatus
    ) {
        this(alertId, decision, resultingStatus, decisionEventId, decidedAt, operationStatus,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    public RegulatedMutationResponseSnapshot(
            String alertId,
            AnalystDecision decision,
            AlertStatus resultingStatus,
            String decisionEventId,
            Instant decidedAt,
            SubmitDecisionOperationStatus operationStatus,
            String outboxAlertId,
            String outboxEventId,
            String outboxDedupeKey,
            String outboxStatus,
            Instant outboxDecidedAt,
            Integer outboxAttempts,
            Instant outboxLastAttemptAt,
            Instant outboxPublishedAt,
            String outboxFailureReason,
            Boolean outboxResolutionPending,
            Instant outboxResolutionRequestedAt,
            String outboxResolutionRequestedBy,
            String outboxResolutionEvidenceType,
            String outboxResolutionEvidenceReference,
            Instant outboxResolutionEvidenceVerifiedAt,
            String outboxResolutionEvidenceVerifiedBy,
            Instant outboxResolutionApprovedAt,
            String outboxResolutionApprovedBy,
            String outboxResolutionApprovalReason
    ) {
        this(alertId, decision, resultingStatus, decisionEventId, decidedAt, operationStatus,
                outboxAlertId, outboxEventId, outboxDedupeKey, outboxStatus, outboxDecidedAt, outboxAttempts,
                outboxLastAttemptAt, outboxPublishedAt, outboxFailureReason, outboxResolutionPending,
                outboxResolutionRequestedAt, outboxResolutionRequestedBy, outboxResolutionEvidenceType,
                outboxResolutionEvidenceReference, outboxResolutionEvidenceVerifiedAt, outboxResolutionEvidenceVerifiedBy,
                outboxResolutionApprovedAt, outboxResolutionApprovedBy, outboxResolutionApprovalReason,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    public static RegulatedMutationResponseSnapshot from(SubmitAnalystDecisionResponse response) {
        return new RegulatedMutationResponseSnapshot(
                response.alertId(),
                response.decision(),
                response.resultingStatus(),
                response.decisionEventId(),
                response.decidedAt(),
                response.operationStatus()
        );
    }

    public SubmitAnalystDecisionResponse toSubmitDecisionResponse() {
        return new SubmitAnalystDecisionResponse(
                alertId,
                decision,
                resultingStatus,
                decisionEventId,
                decidedAt,
                operationStatus
        );
    }

    public static RegulatedMutationResponseSnapshot from(OutboxRecordResponse response) {
        return new RegulatedMutationResponseSnapshot(
                null, null, null, null, null, null,
                response.resourceId(),
                response.eventId(),
                response.dedupeKey(),
                response.status(),
                null,
                response.attempts(),
                null,
                response.publishedAt(),
                response.lastError(),
                response.resolutionPending(),
                response.confirmationUnknownAt(),
                response.resolutionRequestedBy(),
                null,
                null,
                null,
                null,
                response.resolutionApprovedAt(),
                response.resolutionApprovedBy(),
                null
        );
    }

    public OutboxRecordResponse toOutboxRecordResponse() {
        return new OutboxRecordResponse(
                outboxEventId,
                outboxDedupeKey,
                null,
                null,
                outboxAlertId,
                null,
                null,
                outboxStatus,
                outboxAttempts == null ? ZERO : outboxAttempts,
                outboxFailureReason,
                outboxPublishedAt,
                outboxResolutionRequestedAt,
                null,
                outboxResolutionPending == null ? false : outboxResolutionPending,
                null,
                outboxResolutionRequestedBy,
                outboxResolutionRequestedAt,
                outboxResolutionApprovedBy,
                outboxResolutionApprovedAt
        );
    }

    public static RegulatedMutationResponseSnapshot fromFraudCase(FraudCaseDocument document) {
        return new RegulatedMutationResponseSnapshot(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                document.getCaseId(),
                document.getStatus(),
                document.getAnalystId(),
                document.getDecisionReason(),
                document.getDecisionTags() == null ? List.of() : List.copyOf(document.getDecisionTags()),
                document.getDecidedAt(),
                document.getUpdatedAt(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null
        );
    }

    public FraudCaseDocument toFraudCaseDocument() {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId(fraudCaseId);
        document.setStatus(fraudCaseStatus);
        document.setAnalystId(fraudCaseAnalystId);
        document.setDecisionReason(fraudCaseDecisionReason);
        document.setDecisionTags(fraudCaseDecisionTags == null ? List.of() : List.copyOf(fraudCaseDecisionTags));
        document.setDecidedAt(fraudCaseDecidedAt);
        document.setUpdatedAt(fraudCaseUpdatedAt);
        return document;
    }

    public static RegulatedMutationResponseSnapshot fromUpdateFraudCaseResponse(UpdateFraudCaseResponse response) {
        if (response.updatedCase() == null) {
            return new RegulatedMutationResponseSnapshot(
                    null, null, null, null, null, response.operationStatus(),
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    response.caseId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null
            );
        }
        return new RegulatedMutationResponseSnapshot(
                null, null, null, null, null, response.operationStatus(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                response.updatedCase().caseId(),
                response.updatedCase().status(),
                response.updatedCase().analystId(),
                response.updatedCase().decisionReason(),
                response.updatedCase().decisionTags() == null ? List.of() : List.copyOf(response.updatedCase().decisionTags()),
                response.updatedCase().decidedAt(),
                response.updatedCase().updatedAt(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null
        );
    }

    public UpdateFraudCaseResponse toUpdateFraudCaseResponse() {
        FraudCaseDocument document = toFraudCaseDocument();
        com.frauddetection.alert.api.FraudCaseResponse updated = fraudCaseStatus == null ? null : new com.frauddetection.alert.api.FraudCaseResponse(
                document.getCaseId(),
                null,
                null,
                null,
                document.getStatus(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                document.getUpdatedAt(),
                document.getAnalystId(),
                document.getDecisionReason(),
                document.getDecisionTags(),
                document.getDecidedAt(),
                null,
                null,
                null,
                List.of(),
                List.of()
        );
        return new UpdateFraudCaseResponse(
                operationStatus,
                null,
                null,
                fraudCaseId,
                null,
                updated,
                null
        );
    }

    public static RegulatedMutationResponseSnapshot fromTrustIncident(TrustIncidentResponse response) {
        return new RegulatedMutationResponseSnapshot(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                response.incidentId(),
                response.type(),
                response.severity(),
                response.source(),
                response.fingerprint(),
                response.status(),
                response.firstSeenAt(),
                response.lastSeenAt(),
                response.occurrenceCount(),
                response.evidenceRefs() == null ? List.of() : List.copyOf(response.evidenceRefs()),
                response.acknowledgedBy(),
                response.acknowledgedAt(),
                response.acknowledgementReason(),
                response.resolvedBy(),
                response.resolvedAt(),
                response.resolutionReason(),
                response.resolutionEvidence(),
                null
        );
    }

    public static RegulatedMutationResponseSnapshot fromTrustIncidentMaterialization(
            TrustIncidentMaterializationResponse response
    ) {
        return new RegulatedMutationResponseSnapshot(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                response
        );
    }

    public TrustIncidentResponse toTrustIncidentResponse() {
        return new TrustIncidentResponse(
                trustIncidentId,
                trustIncidentType,
                trustIncidentSeverity,
                trustIncidentSource,
                trustIncidentFingerprint,
                trustIncidentStatus,
                trustIncidentFirstSeenAt,
                trustIncidentLastSeenAt,
                trustIncidentOccurrenceCount == null ? 0L : trustIncidentOccurrenceCount,
                trustIncidentEvidenceRefs == null ? List.of() : List.copyOf(trustIncidentEvidenceRefs),
                trustIncidentAcknowledgedBy,
                trustIncidentAcknowledgedAt,
                trustIncidentAcknowledgementReason,
                trustIncidentResolvedBy,
                trustIncidentResolvedAt,
                trustIncidentResolutionReason,
                trustIncidentResolutionEvidence
        );
    }

    public TrustIncidentMaterializationResponse toTrustIncidentMaterializationResponse() {
        return trustIncidentMaterialization;
    }
}
