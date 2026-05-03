package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
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
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public RegulatedMutationRecoveryController(
            RegulatedMutationRecoveryService recoveryService,
            RegulatedMutationInspectionRateLimiter inspectionRateLimiter,
            SensitiveReadAuditService sensitiveReadAuditService
    ) {
        this.recoveryService = recoveryService;
        this.inspectionRateLimiter = inspectionRateLimiter;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @PostMapping("/recover")
    public RegulatedMutationRecoveryRunResponse recover() {
        return recoveryService.recoverNow();
    }

    @GetMapping("/recovery/backlog")
    @AuditedSensitiveRead
    public RegulatedMutationRecoveryBacklogResponse backlog(HttpServletRequest request) {
        RegulatedMutationRecoveryBacklogResponse response = recoveryService.backlog();
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.REGULATED_MUTATION_RECOVERY_BACKLOG,
                ReadAccessResourceType.REGULATED_MUTATION_RECOVERY,
                null,
                1,
                request
        );
        return response;
    }

    @GetMapping("/{idempotencyKey}")
    @AuditedSensitiveRead
    public RegulatedMutationCommandInspectionResponse inspect(
            @PathVariable String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspect(idempotencyKey);
        auditInspection(response, request);
        return response;
    }

    @GetMapping("/by-command/{commandId}")
    @AuditedSensitiveRead
    public RegulatedMutationCommandInspectionResponse inspectByCommandId(
            @PathVariable String commandId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspectByCommandId(commandId);
        auditInspection(response, request);
        return response;
    }

    @GetMapping("/by-idempotency-hash/{hash}")
    @AuditedSensitiveRead
    public RegulatedMutationCommandInspectionResponse inspectByIdempotencyHash(
            @PathVariable String hash,
            Authentication authentication,
            HttpServletRequest request
    ) {
        enforceRateLimit(authentication, request);
        RegulatedMutationCommandInspectionResponse response = recoveryService.inspectByIdempotencyHash(hash);
        auditInspection(response, request);
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

    private void auditInspection(RegulatedMutationCommandInspectionResponse response, HttpServletRequest request) {
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.REGULATED_MUTATION_INSPECTION,
                ReadAccessResourceType.REGULATED_MUTATION_COMMAND,
                response.idempotencyKeyHash(),
                1,
                request
        );
    }
}
