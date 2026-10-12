package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.external.integrity.ExternalAuditIntegrityQuery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ExternalAnchorCoverageCalculator {

    private static final int MAX_MISSING_RANGES = 50;

    private final AuditAnchorRepository anchorRepository;
    private final ExternalAuditAnchorSink sink;
    private final ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository;

    ExternalAnchorCoverageCalculator(
            AuditAnchorRepository anchorRepository,
            ExternalAuditAnchorSink sink,
            ExternalAuditAnchorPublicationStatusRepository publicationStatusRepository
    ) {
        this.anchorRepository = anchorRepository;
        this.sink = sink;
        this.publicationStatusRepository = publicationStatusRepository;
    }

    Snapshot calculate(ExternalAuditIntegrityQuery query) {
        AuditAnchorDocument latestLocal = anchorRepository.findLatestByPartitionKey(query.partitionKey()).orElse(null);
        long latestLocalPosition = latestLocal == null ? 0L : latestLocal.chainPosition();
        ExternalAuditAnchor latestExternal = sink.latest(query.partitionKey()).orElse(null);
        long latestExternalPosition = latestExternal == null ? 0L : latestExternal.chainPosition();
        boolean observedManifestDegradation = isManifestDegraded(latestExternal);
        long from = query.fromPosition() == null
                ? (latestLocalPosition == 0L ? 1L : Math.max(1L, latestLocalPosition - query.limit() + 1L))
                : query.fromPosition();
        long to = latestLocalPosition == 0L ? 0L : Math.min(latestLocalPosition, from + query.limit() - 1L);
        List<ExternalAuditAnchorMissingRange> missingRanges = missingRanges(query.partitionKey(), from, to);
        boolean truncated = latestLocalPosition > to || missingRanges.size() >= MAX_MISSING_RANGES;
        Long timeLagSeconds = latestLocal != null && latestExternal != null
                ? Math.max(0L, Duration.between(latestExternal.createdAt(), latestLocal.createdAt()).toSeconds())
                : null;
        PublicationCoverage publication = publicationCoverage(query.partitionKey(), query.limit());
        boolean manifestDegraded = observedManifestDegradation || publication.manifestDegradedCount() > 0;
        int unrecoveredCount = publication.unrecoveredCount()
                + (observedManifestDegradation && publication.manifestDegradedCount() == 0 ? 1 : 0);
        return new Snapshot(
                latestLocalPosition, latestExternalPosition, timeLagSeconds, missingRanges, truncated, query.limit(),
                manifestDegraded, publication.requiredPublicationFailures(), publication.localStatusUnverified(),
                publication.recoveredCount(), unrecoveredCount
        );
    }

    private List<ExternalAuditAnchorMissingRange> missingRanges(String partitionKey, long from, long to) {
        if (to < from || publicationStatusRepository == null) {
            return List.of();
        }
        List<AuditAnchorDocument> anchors = anchorRepository.findByPartitionKeyAndChainPositionBetween(
                partitionKey, from, to, (int) Math.min(100, to - from + 1));
        Map<Long, AuditAnchorDocument> anchorsByPosition = anchors.stream().collect(Collectors.toMap(
                AuditAnchorDocument::chainPosition, Function.identity(), (left, right) -> left));
        Map<String, ExternalAuditAnchorPublicationStatusDocument> statusesByAnchorId =
                publicationStatusRepository.findByLocalAnchorIds(
                        anchors.stream().map(AuditAnchorDocument::anchorId).toList()
                ).stream().collect(Collectors.toMap(
                        ExternalAuditAnchorPublicationStatusDocument::localAnchorId,
                        Function.identity(),
                        (left, right) -> left
                ));
        List<ExternalAuditAnchorMissingRange> ranges = new ArrayList<>();
        Long openStart = null;
        for (long position = from; position <= to; position++) {
            AuditAnchorDocument anchor = anchorsByPosition.get(position);
            ExternalAuditAnchorPublicationStatusDocument status =
                    anchor == null ? null : statusesByAnchorId.get(anchor.anchorId());
            boolean present = status != null
                    && ExternalAuditAnchor.STATUS_PUBLISHED.equals(status.externalPublicationStatus());
            if (!present && openStart == null) {
                openStart = position;
            }
            if ((present || position == to) && openStart != null) {
                ranges.add(new ExternalAuditAnchorMissingRange(openStart, present ? position - 1 : position));
                openStart = null;
                if (ranges.size() >= MAX_MISSING_RANGES) {
                    return List.copyOf(ranges);
                }
            }
        }
        return List.copyOf(ranges);
    }

    private PublicationCoverage publicationCoverage(String partitionKey, int limit) {
        if (publicationStatusRepository == null) {
            return new PublicationCoverage(0, 0, 0, 0, 0);
        }
        int requiredFailures = 0;
        int localStatusUnverified = 0;
        int recovered = 0;
        int unrecovered = 0;
        int manifestDegraded = 0;
        List<AuditAnchorDocument> anchors = anchorRepository.findHeadWindow(partitionKey, limit);
        if (anchors == null) {
            anchors = List.of();
        }
        for (AuditAnchorDocument anchor : anchors) {
            ExternalAuditAnchorPublicationStatusDocument status =
                    publicationStatusRepository.findByLocalAnchorId(anchor.anchorId()).orElse(null);
            if (status == null) {
                localStatusUnverified++;
                unrecovered++;
            } else if (isManifestDegraded(status)) {
                manifestDegraded++;
                unrecovered++;
            } else if (ExternalAuditAnchor.STATUS_LOCAL_ANCHOR_CREATED_EXTERNAL_REQUIRED_FAILED.equals(
                    status.externalPublicationStatus()) || Boolean.TRUE.equals(status.externalRequired())) {
                requiredFailures++;
                unrecovered++;
            } else if (ExternalAuditAnchor.STATUS_LOCAL_STATUS_UNVERIFIED.equals(status.externalPublicationStatus())) {
                localStatusUnverified++;
                unrecovered++;
            } else if ("RECOVERED".equals(status.localTrackingStatus())) {
                recovered++;
            } else if (ExternalAuditAnchor.STATUS_MISSING.equals(status.externalPublicationStatus())
                    || ExternalAuditAnchor.STATUS_INVALID.equals(status.externalPublicationStatus())
                    || ExternalAuditAnchor.STATUS_FAILED.equals(status.externalPublicationStatus())) {
                unrecovered++;
            }
        }
        return new PublicationCoverage(
                requiredFailures, localStatusUnverified, recovered, unrecovered, manifestDegraded);
    }

    private boolean isManifestDegraded(ExternalAuditAnchor anchor) {
        return anchor != null && ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(anchor.manifestStatus());
    }

    private boolean isManifestDegraded(ExternalAuditAnchorPublicationStatusDocument status) {
        return status != null
                && (ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.headManifestStatus())
                || ExternalAuditAnchor.MANIFEST_STATUS_FAILED.equals(status.manifestStatus()));
    }

    record Snapshot(
            long latestLocalPosition,
            long latestExternalPosition,
            Long timeLagSeconds,
            List<ExternalAuditAnchorMissingRange> missingRanges,
            boolean truncated,
            int limit,
            boolean manifestDegraded,
            int requiredPublicationFailures,
            int localStatusUnverified,
            int recoveredCount,
            int unrecoveredCount
    ) {
    }

    private record PublicationCoverage(
            int requiredPublicationFailures,
            int localStatusUnverified,
            int recoveredCount,
            int unrecoveredCount,
            int manifestDegradedCount
    ) {
    }
}
