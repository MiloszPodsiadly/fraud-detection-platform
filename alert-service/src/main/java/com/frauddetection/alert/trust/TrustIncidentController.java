package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trust/incidents")
public class TrustIncidentController {

    private final TrustIncidentService service;
    private final TrustSignalCollector collector;
    private final TrustIncidentPreviewRateLimiter previewRateLimiter;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public TrustIncidentController(
            TrustIncidentService service,
            TrustSignalCollector collector,
            TrustIncidentPreviewRateLimiter previewRateLimiter,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.service = service;
        this.collector = collector;
        this.previewRateLimiter = previewRateLimiter;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping
    @AuditedSensitiveRead
    public List<TrustIncidentResponse> listOpen(HttpServletRequest request) {
        List<TrustIncidentResponse> response = service.listOpen();
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.TRUST_INCIDENT_LIST,
                ReadAccessResourceType.TRUST_INCIDENT,
                null,
                response.size(),
                request
        );
        return response;
    }

    @GetMapping("/signals/preview")
    @AuditedSensitiveRead
    public TrustSignalPreviewResponse preview(Authentication authentication, HttpServletRequest request) {
        if (!previewRateLimiter.allow(rateLimitIdentity(authentication, request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trust incident signal preview rate limit exceeded.");
        }
        TrustSignalPreviewResponse response = TrustSignalPreviewResponse.from(collector.collect());
        auditPreview(response, request);
        return response;
    }

    @PostMapping("/refresh")
    public TrustIncidentMaterializationResponse refresh(
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            Authentication authentication
    ) {
        return service.refresh(collector.collect(), actor(authentication), idempotencyKey);
    }

    @PostMapping("/{incidentId}/ack")
    public TrustIncidentResponse acknowledge(
            @PathVariable String incidentId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody(required = false) TrustIncidentAcknowledgementRequest request,
            Authentication authentication
    ) {
        return service.acknowledge(incidentId, request == null ? new TrustIncidentAcknowledgementRequest(null) : request, actor(authentication), idempotencyKey);
    }

    @PostMapping("/{incidentId}/resolve")
    public TrustIncidentResponse resolve(
            @PathVariable String incidentId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody TrustIncidentResolutionRequest request,
            Authentication authentication
    ) {
        return service.resolve(incidentId, request, actor(authentication), idempotencyKey);
    }

    private String actor(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }

    private void auditPreview(TrustSignalPreviewResponse response, HttpServletRequest request) {
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.PREVIEW_TRUST_INCIDENT_SIGNALS,
                ReadAccessResourceType.TRUST_INCIDENT_SIGNAL,
                null,
                response.signalCount(),
                request
        );
    }

    private String rateLimitIdentity(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return "actor:" + authentication.getName();
        }
        return "ip:" + (request == null ? "unknown" : request.getRemoteAddr());
    }
}
