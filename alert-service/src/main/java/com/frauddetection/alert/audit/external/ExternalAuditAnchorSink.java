package com.frauddetection.alert.audit.external;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalAuditAnchorSink {

    /*
     * Production external anchor sinks must provide append-only semantics,
     * independent storage outside Mongo, no overwrite/delete paths, bounded reads,
     * and durable retention controls outside the application database.
     */

    String sinkType();

    ExternalAuditAnchor publish(ExternalAuditAnchor anchor);

    Optional<ExternalAuditAnchor> latest(String partitionKey);

    List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit);
}
