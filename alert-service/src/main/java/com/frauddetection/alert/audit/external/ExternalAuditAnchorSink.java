package com.frauddetection.alert.audit.external;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalAuditAnchorSink {

    String sinkType();

    ExternalAuditAnchor publish(ExternalAuditAnchor anchor);

    Optional<ExternalAuditAnchor> latest(String partitionKey);

    List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit);
}
