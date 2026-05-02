package com.frauddetection.alert.trust;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrustIncidentRepository extends MongoRepository<TrustIncidentDocument, String> {

    Optional<TrustIncidentDocument> findFirstByTypeAndSourceAndFingerprintAndStatusInOrderByUpdatedAtDesc(
            String type,
            String source,
            String fingerprint,
            Collection<TrustIncidentStatus> statuses
    );

    List<TrustIncidentDocument> findTop100ByStatusInOrderByUpdatedAtDesc(Collection<TrustIncidentStatus> statuses);

    long countByStatusInAndSeverity(Collection<TrustIncidentStatus> statuses, TrustIncidentSeverity severity);

    long countByStatusInAndSeverityAndAcknowledgedAtIsNull(
            Collection<TrustIncidentStatus> statuses,
            TrustIncidentSeverity severity
    );

    Optional<TrustIncidentDocument> findTopByStatusInOrderByFirstSeenAtAsc(Collection<TrustIncidentStatus> statuses);

    long countByStatusInAndSeverityAndFirstSeenAtBefore(
            Collection<TrustIncidentStatus> statuses,
            TrustIncidentSeverity severity,
            Instant firstSeenAt
    );
}
