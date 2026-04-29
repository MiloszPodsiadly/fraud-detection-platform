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

    default ExternalImmutabilityLevel immutabilityLevel() {
        return ExternalImmutabilityLevel.NONE;
    }

    default ExternalWitnessCapabilities capabilities() {
        return ExternalWitnessCapabilities.disabled();
    }

    ExternalAuditAnchor publish(ExternalAuditAnchor anchor);

    default java.util.Optional<ExternalAnchorReference> externalReference(ExternalAuditAnchor anchor) {
        return java.util.Optional.empty();
    }

    Optional<ExternalAuditAnchor> latest(String partitionKey);

    default Optional<ExternalAuditAnchor> findByChainPosition(String partitionKey, long chainPosition) {
        return Optional.empty();
    }

    List<ExternalAuditAnchor> findByRange(String partitionKey, Instant from, Instant to, int limit);
}
