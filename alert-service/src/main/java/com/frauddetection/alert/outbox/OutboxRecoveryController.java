package com.frauddetection.alert.outbox;

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

    public OutboxRecoveryController(OutboxRecoveryService service) {
        this.service = service;
    }

    @GetMapping("/recovery/backlog")
    public OutboxBacklogResponse backlog() {
        return service.backlog();
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
