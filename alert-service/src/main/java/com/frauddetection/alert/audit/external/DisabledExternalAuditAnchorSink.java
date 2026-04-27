package com.frauddetection.alert.audit.external;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

class DisabledExternalAuditAnchorSink implements ExternalAuditAnchorSink {

    @Override
    public String sinkType() {
        return "disabled";
    }

    @Override
    public ExternalAuditAnchor publish(ExternalAuditAnchor anchor) {
        throw new ExternalAuditAnchorSinkException("DISABLED", "External audit anchoring is disabled.");
    }

    @Override
    public Optional<ExternalAuditAnchor> latest(String partitionKey) {
        return Optional.empty();
    }

    @Override
    public List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit) {
        return List.of();
    }
}
