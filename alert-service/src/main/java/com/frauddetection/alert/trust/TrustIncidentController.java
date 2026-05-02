package com.frauddetection.alert.trust;

import com.frauddetection.alert.audit.read.ReadAccessAuditOutcome;
import com.frauddetection.alert.audit.read.ReadAccessAuditService;
import com.frauddetection.alert.audit.read.ReadAccessAuditTarget;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/trust/incidents")
public class TrustIncidentController {

    private final TrustIncidentService service;
    private final TrustSignalCollector collector;
    private final TrustIncidentPreviewRateLimiter previewRateLimiter;
    private final ReadAccessAuditService readAccessAuditService;
    private final Environment environment;
    private final boolean bankModeFailClosed;

    public TrustIncidentController(
            TrustIncidentService service,
            TrustSignalCollector collector,
            TrustIncidentPreviewRateLimiter previewRateLimiter,
            ReadAccessAuditService readAccessAuditService,
            Environment environment,
            @Value("${app.audit.bank-mode.fail-closed:false}") boolean bankModeFailClosed
    ) {
        this.service = service;
        this.collector = collector;
        this.previewRateLimiter = previewRateLimiter;
        this.readAccessAuditService = readAccessAuditService;
        this.environment = environment;
        this.bankModeFailClosed = bankModeFailClosed;
    }

    @GetMapping
    public List<TrustIncidentResponse> listOpen() {
        return service.listOpen();
    }

    @GetMapping("/signals/preview")
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
        ReadAccessAuditTarget target = new ReadAccessAuditTarget(
                ReadAccessEndpointCategory.PREVIEW_TRUST_INCIDENT_SIGNALS,
                ReadAccessResourceType.TRUST_INCIDENT_SIGNAL,
                null,
                null,
                null,
                response.signalCount()
        );
        String correlationId = request == null ? null : request.getHeader("X-Correlation-Id");
        if (prodLike()) {
            try {
                readAccessAuditService.auditOrThrow(target, ReadAccessAuditOutcome.SUCCESS, response.signalCount(), correlationId);
            } catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Trust incident signal preview audit unavailable.");
            }
            return;
        }
        readAccessAuditService.audit(target, ReadAccessAuditOutcome.SUCCESS, response.signalCount(), correlationId);
    }

    private boolean prodLike() {
        return bankModeFailClosed || Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging")
                        || profile.equals("bank"));
    }

    private String rateLimitIdentity(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return "actor:" + authentication.getName();
        }
        return "ip:" + (request == null ? "unknown" : request.getRemoteAddr());
    }
}
