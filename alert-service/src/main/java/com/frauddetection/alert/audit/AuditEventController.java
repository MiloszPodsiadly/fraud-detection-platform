package com.frauddetection.alert.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditEventController {

    private final AuditEventReadService auditEventReadService;

    public AuditEventController(AuditEventReadService auditEventReadService) {
        this.auditEventReadService = auditEventReadService;
    }

    @GetMapping
    public AuditEventReadResponse listAuditEvents(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "actor_id", required = false) String actorId,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit
    ) {
        return auditEventReadService.readEvents(eventType, actorId, resourceType, resourceId, from, to, limit);
    }
}
