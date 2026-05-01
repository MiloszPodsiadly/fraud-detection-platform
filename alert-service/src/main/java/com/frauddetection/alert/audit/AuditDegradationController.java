package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.security.principal.CurrentAnalystUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit/degradations")
public class AuditDegradationController {

    private final AuditDegradationService service;
    private final CurrentAnalystUser currentAnalystUser;

    public AuditDegradationController(AuditDegradationService service, CurrentAnalystUser currentAnalystUser) {
        this.service = service;
        this.currentAnalystUser = currentAnalystUser;
    }

    @GetMapping
    public AuditDegradationListResponse listUnresolved() {
        return new AuditDegradationListResponse(
                service.unresolvedEvents().stream().map(AuditDegradationResponse::from).toList()
        );
    }

    @PostMapping("/{auditId}/resolve")
    @ResponseStatus(HttpStatus.OK)
    public AuditDegradationResponse resolve(
            @PathVariable String auditId,
            @Valid @RequestBody ResolveAuditDegradationRequest request
    ) {
        String actor = currentAnalystUser.get()
                .map(principal -> principal.userId())
                .orElse("unknown");
        return AuditDegradationResponse.from(service.resolveDegradation(
                auditId,
                actor,
                request.reason(),
                request.evidenceReference()
        ));
    }

    public record ResolveAuditDegradationRequest(
            @NotBlank
            @Size(max = 500)
            String reason,

            @JsonProperty("evidence_reference")
            @Valid
            ResolutionEvidenceReference evidenceReference
    ) {
    }

    public record AuditDegradationListResponse(List<AuditDegradationResponse> events) {
    }

    public record AuditDegradationResponse(
            @JsonProperty("audit_id")
            String auditId,
            String type,
            @JsonProperty("resource_type")
            String resourceType,
            @JsonProperty("resource_id")
            String resourceId,
            String operation,
            Instant timestamp,
            boolean resolved,
            @JsonProperty("resolution_pending")
            boolean resolutionPending,
            @JsonProperty("resolution_requested_at")
            Instant resolutionRequestedAt,
            @JsonProperty("resolution_requested_by")
            String resolutionRequestedBy,
            @JsonProperty("resolved_at")
            Instant resolvedAt,
            @JsonProperty("resolved_by")
            String resolvedBy,
            @JsonProperty("resolution_reason")
            String resolutionReason,
            @JsonProperty("resolution_evidence_type")
            String resolutionEvidenceType,
            @JsonProperty("resolution_evidence_reference")
            String resolutionEvidenceReference,
            @JsonProperty("resolution_evidence_verified_at")
            Instant resolutionEvidenceVerifiedAt,
            @JsonProperty("resolution_evidence_verified_by")
            String resolutionEvidenceVerifiedBy,
            @JsonProperty("approved_at")
            Instant approvedAt,
            @JsonProperty("approved_by")
            String approvedBy,
            @JsonProperty("approval_reason")
            String approvalReason
    ) {
        static AuditDegradationResponse from(AuditDegradationEventDocument document) {
            return new AuditDegradationResponse(
                    document.getAuditId(),
                    document.getType(),
                    document.getResourceType(),
                    document.getResourceId(),
                    document.getOperation(),
                    document.getTimestamp(),
                    document.isResolved(),
                    document.isResolutionPending(),
                    document.getResolutionRequestedAt(),
                    document.getResolutionRequestedBy(),
                    document.getResolvedAt(),
                    document.getResolvedBy(),
                    document.getResolutionReason(),
                    document.getResolutionEvidenceType(),
                    document.getResolutionEvidenceReference(),
                    document.getResolutionEvidenceVerifiedAt(),
                    document.getResolutionEvidenceVerifiedBy(),
                    document.getApprovedAt(),
                    document.getApprovedBy(),
                    document.getApprovalReason()
            );
        }
    }
}
