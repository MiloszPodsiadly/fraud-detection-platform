package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/regulated-mutations")
public class RegulatedMutationRecoveryController {

    private final RegulatedMutationRecoveryService recoveryService;
    private final RegulatedMutationInspectionRateLimiter inspectionRateLimiter;
    private final AuditService auditService;

    public RegulatedMutationRecoveryController(
            RegulatedMutationRecoveryService recoveryService,
            RegulatedMutationInspectionRateLimiter inspectionRateLimiter,
            AuditService auditService
    ) {
        this.recoveryService = recoveryService;
        this.inspectionRateLimiter = inspectionRateLimiter;
        this.auditService = auditService;
    }

    @PostMapping("/recover")
    public RegulatedMutationRecoveryRunResponse recover() {
        return recoveryService.recoverNow();
    }

    @GetMapping("/recovery/backlog")
    public RegulatedMutationRecoveryBacklogResponse backlog() {
        return recoveryService.backlog();
    }

    @GetMapping("/{idempotencyKey}")
    public RegulatedMutationCommandInspectionResponse inspect(
            @PathVariable String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspect(idempotencyKey);
        auditInspection(response, authentication, "RAW_IDEMPOTENCY_KEY");
        return response;
    }

    @GetMapping("/by-command/{commandId}")
    public RegulatedMutationCommandInspectionResponse inspectByCommandId(
            @PathVariable String commandId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspectByCommandId(commandId);
        auditInspection(response, authentication, "COMMAND_ID");
        return response;
    }

    @GetMapping("/by-idempotency-hash/{hash}")
    public RegulatedMutationCommandInspectionResponse inspectByIdempotencyHash(
            @PathVariable String hash,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspectByIdempotencyHash(hash);
        auditInspection(response, authentication, "IDEMPOTENCY_HASH");
        return response;
    }

    private void enforceRateLimit(Authentication authentication, HttpServletRequest request) {
        if (!inspectionRateLimiter.allow(rateLimitIdentity(authentication, request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Regulated mutation inspection rate limit exceeded.");
        }
    }

    private String rateLimitIdentity(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return "actor:" + authentication.getName();
        }
        return "ip:" + (request == null ? "unknown" : request.getRemoteAddr());
    }

    private void auditInspection(RegulatedMutationCommandInspectionResponse response, Authentication authentication, String lookupMode) {
        try {
            auditService.audit(
                    AuditAction.INSPECT_REGULATED_MUTATION_COMMAND,
                    AuditResourceType.REGULATED_MUTATION_COMMAND,
                    response.idempotencyKeyHash(),
                    null,
                    authentication == null ? null : authentication.getName(),
                    AuditOutcome.SUCCESS,
                    null,
                    new AuditEventMetadataSummary(null, lookupMode, "alert-service", "1.0", null, null, null, null, null)
            );
        } catch (RuntimeException exception) {
            throw new AuditPersistenceUnavailableException();
        }
    }
}
