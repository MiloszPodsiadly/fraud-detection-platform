package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.read.AuditedSensitiveRead;
import com.frauddetection.alert.audit.read.ReadAccessEndpointCategory;
import com.frauddetection.alert.audit.read.ReadAccessResourceType;
import com.frauddetection.alert.audit.read.SensitiveReadAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/events")
public class AuditEventController {

    private final AuditEventReadService auditEventReadService;
    private final SensitiveReadAuditService sensitiveReadAuditService;

    public AuditEventController(AuditEventReadService auditEventReadService, SensitiveReadAuditService sensitiveReadAuditService) {
        this.auditEventReadService = auditEventReadService;
        this.sensitiveReadAuditService = sensitiveReadAuditService;
    }

    @GetMapping
    @AuditedSensitiveRead
    public AuditEventReadResponse listAuditEvents(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "actor_id", required = false) String actorId,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request
    ) {
        AuditEventReadResponse response = auditEventReadService.readEvents(eventType, actorId, resourceType, resourceId, from, to, limit);
        sensitiveReadAuditService.audit(
                ReadAccessEndpointCategory.AUDIT_EVENT_LIST,
                ReadAccessResourceType.AUDIT_EVENT,
                null,
                response.count(),
                request
        );
        return response;
    }
}
