package com.frauddetection.alert.trust;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trust/incidents")
public class TrustIncidentController {

    private final TrustIncidentService service;
    private final TrustSignalCollector collector;

    public TrustIncidentController(TrustIncidentService service, TrustSignalCollector collector) {
        this.service = service;
        this.collector = collector;
    }

    @GetMapping
    public List<TrustIncidentResponse> listOpen() {
        return service.listOpen(collector.collect());
    }

    @PostMapping("/{incidentId}/ack")
    public TrustIncidentResponse acknowledge(
            @PathVariable String incidentId,
            @Valid @RequestBody(required = false) TrustIncidentAcknowledgementRequest request,
            Authentication authentication
    ) {
        return service.acknowledge(incidentId, request == null ? new TrustIncidentAcknowledgementRequest(null) : request, actor(authentication));
    }

    @PostMapping("/{incidentId}/resolve")
    public TrustIncidentResponse resolve(
            @PathVariable String incidentId,
            @Valid @RequestBody TrustIncidentResolutionRequest request,
            Authentication authentication
    ) {
        return service.resolve(incidentId, request, actor(authentication));
    }

    private String actor(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
