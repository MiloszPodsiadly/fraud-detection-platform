package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class ExternalAuditAnchorPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuditAnchorPublisher.class);
    private static final String DEFAULT_PARTITION_KEY = "source_service:alert-service";

    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository;
    private final ExternalAuditAnchorSink sink;
    private final AuditTrustAuthorityClient trustAuthorityClient;
    private final AuditTrustAuthorityProperties trustAuthorityProperties;
    private final AlertServiceMetrics metrics;
    private final Clock clock;
    private final int defaultLimit;

    @Autowired
    public ExternalAuditAnchorPublisher(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            ExternalAuditAnchorSink sink,
            AuditTrustAuthorityClient trustAuthorityClient,
            AuditTrustAuthorityProperties trustAuthorityProperties,
            AlertServiceMetrics metrics,
            @Value("${app.audit.external-anchoring.publish-limit:100}") int defaultLimit
    ) {
        this(anchorRepository, publicationStatusRepository, sink, trustAuthorityClient, trustAuthorityProperties, metrics, Clock.systemUTC(), defaultLimit);
    }

    ExternalAuditAnchorPublisher(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            ExternalAuditAnchorSink sink,
            AlertServiceMetrics metrics,
            Clock clock,
            int defaultLimit
    ) {
        this(
                anchorRepository,
                publicationStatusRepository,
                sink,
                new DisabledAuditTrustAuthorityClient(),
                new AuditTrustAuthorityProperties(),
                metrics,
                clock,
                defaultLimit
        );
    }

    ExternalAuditAnchorPublisher(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository,
            ExternalAuditAnchorSink sink,
            AuditTrustAuthorityClient trustAuthorityClient,
            AuditTrustAuthorityProperties trustAuthorityProperties,
            AlertServiceMetrics metrics,
            Clock clock,
            int defaultLimit
    ) {
        this.anchorRepository = anchorRepository;
        this.publicationStatusRepository = publicationStatusRepository;
        this.sink = sink;
        this.trustAuthorityClient = trustAuthorityClient == null ? new DisabledAuditTrustAuthorityClient() : trustAuthorityClient;
        this.trustAuthorityProperties = trustAuthorityProperties == null ? new AuditTrustAuthorityProperties() : trustAuthorityProperties;
        this.metrics = metrics;
        this.clock = clock;
        this.defaultLimit = defaultLimit > 0 ? Math.min(defaultLimit, 500) : 100;
    }

    public ExternalAuditAnchorPublishResult publishDefaultWindow() {
        return publishHeadWindow(defaultLimit);
    }

    public ExternalAuditAnchorPublishResult publishHeadWindow(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        int published = 0;
        int unverified = 0;
        int duplicates = 0;
        int failed = 0;
        try {
            long externalPosition = sink.latest(DEFAULT_PARTITION_KEY)
                    .map(ExternalAuditAnchor::chainPosition)
                    .orElse(0L);
            List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionGreaterThan(
                    DEFAULT_PARTITION_KEY,
                    externalPosition,
                    boundedLimit
            );
            for (AuditAnchorDocument localAnchor : anchors) {
                try {
                    ExternalAuditAnchor candidate = ExternalAuditAnchor.from(localAnchor, sink.sinkType());
                    ExternalAuditAnchor stored = sink.publish(candidate);
                    if (ExternalAuditAnchor.STATUS_UNVERIFIED.equals(stored.publicationStatus())) {
                        metrics.recordExternalAnchorPublished(sink.sinkType(), "UNVERIFIED");
                        unverified++;
                        recordPublicationPartial(localAnchor, stored);
                    } else if (ExternalAuditAnchor.STATUS_INVALID.equals(stored.publicationStatus())
                            || ExternalAuditAnchor.STATUS_CONFLICT.equals(stored.publicationStatus())) {
                        metrics.recordExternalAnchorPublished(sink.sinkType(), stored.publicationStatus());
                        metrics.recordExternalAnchorPublishFailed(sink.sinkType(), stored.publicationReason());
                        failed++;
                        recordPublicationFailure(localAnchor, stored.publicationReason());
                    } else if (candidate.externalAnchorId().equals(stored.externalAnchorId())) {
                        if (recordPublicationSuccess(localAnchor, stored)) {
                            metrics.recordExternalAnchorPublished(sink.sinkType(), "PUBLISHED");
                            published++;
                        } else {
                            metrics.recordExternalAnchorPublished(sink.sinkType(), "UNVERIFIED");
                            unverified++;
                        }
                    } else {
                        if (recordPublicationSuccess(localAnchor, stored)) {
                            metrics.recordExternalAnchorPublished(sink.sinkType(), "DUPLICATE");
                            duplicates++;
                        } else {
                            metrics.recordExternalAnchorPublished(sink.sinkType(), "UNVERIFIED");
                            unverified++;
                        }
                    }
                    recordLag(localAnchor.createdAt());
                } catch (ExternalAuditAnchorSinkException exception) {
                    metrics.recordExternalAnchorPublished(sink.sinkType(), "FAILED");
                    metrics.recordExternalAnchorPublishFailed(sink.sinkType(), exception.reason());
                    failed++;
                    recordPublicationFailure(localAnchor, exception.reason());
                    log.warn("External audit anchor publication failed: reason={}", exception.reason());
                }
            }
            return new ExternalAuditAnchorPublishResult(published, unverified, duplicates, failed, boundedLimit);
        } catch (DataAccessException exception) {
            metrics.recordExternalAnchorPublishFailed(sink.sinkType(), "UNAVAILABLE");
            log.warn("Local audit anchor lookup failed for external publication.");
            return new ExternalAuditAnchorPublishResult(0, 0, 0, 1, boundedLimit);
        } catch (ExternalAuditAnchorSinkException exception) {
            metrics.recordExternalAnchorPublishFailed(sink.sinkType(), exception.reason());
            log.warn("External audit anchor lookup failed before publication: reason={}", exception.reason());
            return new ExternalAuditAnchorPublishResult(0, 0, 0, 1, boundedLimit);
        }
    }

    private boolean recordPublicationSuccess(AuditAnchorDocument localAnchor, ExternalAuditAnchor stored) {
        try {
            ExternalAnchorReference reference = sink.externalReference(stored).orElse(null);
            ExternalImmutabilityLevel immutabilityLevel = sink.immutabilityLevel() == null
                    ? ExternalImmutabilityLevel.NONE
                    : sink.immutabilityLevel();
            SignedAuditAnchorPayload signature = sign(localAnchor, stored, reference, immutabilityLevel);
            if ("SIGNATURE_FAILED".equals(signature.signatureStatus())) {
                publicationStatusRepository.recordPartial(
                        localAnchor,
                        clock.instant(),
                        sink.sinkType(),
                        reference,
                        immutabilityLevel,
                        "SIGNATURE_FAILED",
                        null,
                        signature
                );
                return false;
            }
            publicationStatusRepository.recordSuccess(localAnchor, clock.instant(), sink.sinkType(), reference, immutabilityLevel, signature);
            return true;
        } catch (DataAccessException exception) {
            log.warn("External audit anchor publication status update failed after publish.");
            return true;
        } catch (ExternalAuditAnchorSinkException exception) {
            metrics.recordExternalAnchorPublishFailed(sink.sinkType(), exception.reason());
            recordPublicationFailure(localAnchor, exception.reason());
            log.warn("External audit anchor reference verification failed after publish: reason={}", exception.reason());
            return false;
        }
    }

    private void recordPublicationPartial(AuditAnchorDocument localAnchor, ExternalAuditAnchor stored) {
        try {
            ExternalAnchorReference reference = sink.externalReference(stored).orElse(null);
            ExternalImmutabilityLevel immutabilityLevel = sink.immutabilityLevel() == null
                    ? ExternalImmutabilityLevel.NONE
                    : sink.immutabilityLevel();
            publicationStatusRepository.recordPartial(
                    localAnchor,
                    clock.instant(),
                    sink.sinkType(),
                    reference,
                    immutabilityLevel,
                    stored.publicationReason(),
                    stored.manifestStatus(),
                    SignedAuditAnchorPayload.failed()
            );
        } catch (DataAccessException exception) {
            log.warn("External audit anchor partial publication status update failed.");
        } catch (ExternalAuditAnchorSinkException exception) {
            metrics.recordExternalAnchorPublishFailed(sink.sinkType(), exception.reason());
            recordPublicationFailure(localAnchor, exception.reason());
            log.warn("External audit anchor reference verification failed after partial publish: reason={}", exception.reason());
        }
    }

    private SignedAuditAnchorPayload sign(
            AuditAnchorDocument localAnchor,
            ExternalAuditAnchor stored,
            ExternalAnchorReference reference,
            ExternalImmutabilityLevel immutabilityLevel
    ) {
        if (!trustAuthorityClient.enabled()) {
            return SignedAuditAnchorPayload.unsigned();
        }
        if (reference == null) {
            return trustAuthorityProperties.isSigningRequired() ? SignedAuditAnchorPayload.failed() : SignedAuditAnchorPayload.unavailable();
        }
        SignedAuditAnchorPayload signature = trustAuthorityClient.sign(new AuditAnchorSigningPayload(
                stored.partitionKey(),
                localAnchor.anchorId(),
                localAnchor.chainPosition(),
                localAnchor.lastEventHash(),
                reference.externalKey(),
                reference.externalHash(),
                immutabilityLevel
        ));
        if ("SIGNATURE_UNAVAILABLE".equals(signature.signatureStatus()) && trustAuthorityProperties.isSigningRequired()) {
            return SignedAuditAnchorPayload.failed();
        }
        return signature;
    }

    private void recordPublicationFailure(AuditAnchorDocument localAnchor, String reason) {
        try {
            publicationStatusRepository.recordFailure(localAnchor, clock.instant(), reason);
        } catch (DataAccessException exception) {
            log.warn("External audit anchor publication failure status update failed.");
        }
    }

    private void recordLag(Instant localAnchorCreatedAt) {
        if (localAnchorCreatedAt != null) {
            metrics.recordExternalAnchorLag(Duration.between(localAnchorCreatedAt, clock.instant()));
        }
    }
}
