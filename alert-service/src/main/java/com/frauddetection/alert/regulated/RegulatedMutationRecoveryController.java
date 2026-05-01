package com.frauddetection.alert.regulated;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/regulated-mutations")
public class RegulatedMutationRecoveryController {

    private final RegulatedMutationRecoveryService recoveryService;

    public RegulatedMutationRecoveryController(RegulatedMutationRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
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
    public RegulatedMutationCommandInspectionResponse inspect(@PathVariable String idempotencyKey) {
        return recoveryService.inspect(idempotencyKey);
    }
}
