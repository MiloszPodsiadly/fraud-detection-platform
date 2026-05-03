package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxRecoveryController {

    private final OutboxRecoveryService service;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public OutboxRecoveryController(OutboxRecoveryService service, SensitiveReadAuditService sensitiveReadAuditService) {
        this.service = service;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping("/recovery/backlog")
    @AuditedSensitiveRead
    public OutboxBacklogResponse backlog(HttpServletRequest request) {
        OutboxBacklogResponse response = service.backlog();
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.OUTBOX_RECOVERY_BACKLOG,
                ReadAccessResourceType.OUTBOX_RECOVERY,
                null,
                1,
                request
        );
        return response;
    }

    @PostMapping("/recovery/run")
    public OutboxRecoveryRunResponse recoverNow() {
        return service.recoverNow();
    }

    @PostMapping("/{eventId}/resolve-confirmation")
    public OutboxRecordResponse resolveConfirmation(
            @PathVariable String eventId,
            @RequestHeader(name = "X-Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody OutboxConfirmationResolutionRequest request,
            Authentication authentication
    ) {
        return OutboxRecordResponse.from(service.resolveConfirmation(eventId, request, authentication == null ? null : authentication.getName(), idempotencyKey));
    }
}
