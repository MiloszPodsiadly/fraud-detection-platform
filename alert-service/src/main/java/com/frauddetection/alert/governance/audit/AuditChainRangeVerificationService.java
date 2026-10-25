package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.AuditAnchorDocument;
import com.frauddetection.alert.audit.AuditAnchorRepository;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventHasher;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded verification of a contiguous, inclusive range in the platform audit partition. */
@Service
public class AuditChainRangeVerificationService {
    private static final String PARTITION = "source_service:alert-service";
    private static final int HARD_MAX_RANGE = 10_000;

    private final AuditEventRepository events;
    private final AuditAnchorRepository anchors;
    private final int maxRangeLength;

    public AuditChainRangeVerificationService(AuditEventRepository events, AuditAnchorRepository anchors,
            @Value("${app.audit.integrity.max-range-length:1000}") int maxRangeLength) {
        this.events = events;
        this.anchors = anchors;
        this.maxRangeLength = Math.max(1, Math.min(HARD_MAX_RANGE, maxRangeLength));
    }

    public Result verify(PolicyGovernanceActor actor, long startPosition, long endPosition,
            Long requestedBoundaryPosition, String requestedBoundaryHash) {
        requireReader(actor);
        if (startPosition < 1 || endPosition < startPosition) {
            return result(Status.INVALID_REQUEST, startPosition, endPosition, null, null, 0,
                    null, false, "INVALID_RANGE");
        }
        long length;
        try {
            length = Math.addExact(Math.subtractExact(endPosition, startPosition), 1);
        } catch (ArithmeticException overflow) {
            return result(Status.RANGE_TOO_LARGE, startPosition, endPosition, null, null, 0,
                    null, false, "RANGE_LENGTH_OVERFLOW");
        }
        if (length > maxRangeLength) {
            return result(Status.RANGE_TOO_LARGE, startPosition, endPosition, null, null, 0,
                    null, false, "CONFIGURED_RANGE_LIMIT_EXCEEDED");
        }
        if ((requestedBoundaryPosition == null) != (requestedBoundaryHash == null)
                || requestedBoundaryHash != null && !requestedBoundaryHash.matches("[a-fA-F0-9]{64}")) {
            return result(Status.INVALID_REQUEST, startPosition, endPosition, null, null, 0,
                    null, false, "BOUNDARY_POSITION_AND_SHA256_REQUIRED_TOGETHER");
        }

        HeadSnapshot initial = stableHead();
        AuditEventDocument initialHead = initial.head();
        if (!initial.stable()) {
            return result(Status.UNSTABLE_BOUNDARY, startPosition, endPosition, null, null, 0,
                    initialHead == null ? null : initialHead.chainPosition(), false, "HEAD_MOVED_DURING_SNAPSHOT");
        }
        if (!validHead(initialHead)) {
            return result(Status.INVALID, startPosition, endPosition, null, null, 0,
                    initialHead == null ? null : initialHead.chainPosition(), false, "CURRENT_HEAD_INVALID");
        }
        long boundaryPosition = requestedBoundaryPosition == null
                ? initialHead.chainPosition() : requestedBoundaryPosition;
        String boundaryHash = requestedBoundaryHash == null ? initialHead.eventHash() : requestedBoundaryHash;
        if (boundaryPosition < endPosition || boundaryPosition > initialHead.chainPosition()) {
            return result(Status.INVALID_REQUEST, startPosition, endPosition, boundaryPosition, boundaryHash, 0,
                    initialHead.chainPosition(), false, "RANGE_OUTSIDE_STABLE_BOUNDARY");
        }
        AuditEventDocument boundary = exactEvent(boundaryPosition);
        if (boundary == null || !Objects.equals(boundaryHash, boundary.eventHash()) || !validEvent(boundary)
                || !validAnchor(exactAnchor(boundaryPosition), boundary)) {
            return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash, 0,
                    initialHead.chainPosition(), false, "STABLE_BOUNDARY_INVALID");
        }

        long queryFrom = startPosition == 1 ? 1 : startPosition - 1;
        List<AuditEventDocument> rows = events.findByPartitionKeyAndChainPositionBetween(
                PARTITION, queryFrom, endPosition, Math.toIntExact(length + (startPosition == 1 ? 1 : 2)));
        if (rows.stream().map(AuditEventDocument::chainPosition).distinct().count() != rows.size()) {
            return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash,
                    0, initialHead.chainPosition(), false, "DUPLICATE_EVENT_POSITION");
        }
        if (rows.size() != length + (startPosition == 1 ? 0 : 1)) {
            return result(Status.TRUNCATED, startPosition, endPosition, boundaryPosition, boundaryHash,
                    Math.max(0, rows.size() - (startPosition == 1 ? 0 : 1)), initialHead.chainPosition(), false,
                    "REQUESTED_RANGE_OR_PREDECESSOR_MISSING");
        }

        AuditEventDocument predecessor = startPosition == 1 ? null : rows.getFirst();
        List<AuditEventDocument> range = startPosition == 1 ? rows : rows.subList(1, rows.size());
        if (predecessor == null ? !Objects.equals(range.getFirst().previousEventHash(), null)
                : !validEvent(predecessor) || predecessor.chainPosition() != startPosition - 1
                    || !validAnchor(exactAnchor(startPosition - 1), predecessor)
                    || !Objects.equals(range.getFirst().previousEventHash(), predecessor.eventHash())) {
            return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash, 0,
                    initialHead.chainPosition(), false, "PREDECESSOR_LINK_INVALID");
        }

        List<AuditAnchorDocument> rangeAnchors = anchors.findByPartitionKeyAndChainPositionBetween(
                PARTITION, startPosition, endPosition, Math.toIntExact(length + 1));
        if (rangeAnchors.stream().map(AuditAnchorDocument::chainPosition).distinct().count() != rangeAnchors.size()) {
            return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash,
                    0, initialHead.chainPosition(), false, "DUPLICATE_ANCHOR_POSITION");
        }
        if (rangeAnchors.size() != length) {
            return result(Status.TRUNCATED, startPosition, endPosition, boundaryPosition, boundaryHash,
                    0, initialHead.chainPosition(), false, "RANGE_ANCHOR_MISSING_OR_DUPLICATED");
        }
        Map<Long, AuditAnchorDocument> byPosition = new HashMap<>();
        for (AuditAnchorDocument anchor : rangeAnchors) {
            if (byPosition.putIfAbsent(anchor.chainPosition(), anchor) != null) {
                return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash,
                        0, initialHead.chainPosition(), false, "DUPLICATE_ANCHOR_POSITION");
            }
        }
        AuditEventDocument previous = predecessor;
        for (int offset = 0; offset < range.size(); offset++) {
            AuditEventDocument current = range.get(offset);
            long expected = startPosition + offset;
            if (!validEvent(current) || current.chainPosition() != expected
                    || !Objects.equals(current.previousEventHash(), previous == null ? null : previous.eventHash())
                    || !validAnchor(byPosition.get(expected), current)) {
                return result(Status.INVALID, startPosition, endPosition, boundaryPosition, boundaryHash,
                        offset, initialHead.chainPosition(), false, "EVENT_CHAIN_OR_ANCHOR_INVALID_AT_" + expected);
            }
            previous = current;
        }

        HeadSnapshot finalSnapshot = stableHead();
        AuditEventDocument finalHead = finalSnapshot.head();
        AuditEventDocument boundaryAfter = exactEvent(boundaryPosition);
        AuditAnchorDocument boundaryAnchorAfter = exactAnchor(boundaryPosition);
        if (!finalSnapshot.stable() || !validHead(finalHead) || boundaryAfter == null || !validEvent(boundaryAfter)
                || !Objects.equals(boundaryHash, boundaryAfter.eventHash())
                || !validAnchor(boundaryAnchorAfter, boundaryAfter)) {
            return result(Status.UNSTABLE_BOUNDARY, startPosition, endPosition, boundaryPosition, boundaryHash,
                    range.size(), finalHead == null ? null : finalHead.chainPosition(), false,
                    "BOUNDARY_CHANGED_DURING_VERIFICATION");
        }
        boolean concurrentAppend = finalHead.chainPosition() > initialHead.chainPosition();
        return new Result(Status.CHAIN_RANGE_VERIFIED, Dimension.VERIFIED, Dimension.NOT_ASSESSED,
                Dimension.NOT_ASSESSED, Dimension.NOT_ASSESSED,
                startPosition, endPosition, boundaryPosition, boundaryHash, range.size(),
                finalHead.chainPosition(), concurrentAppend, null, range.getLast().eventHash());
    }

    private AuditEventDocument exactEvent(long position) {
        List<AuditEventDocument> found = events.findByPartitionKeyAndChainPositionBetween(
                PARTITION, position, position, 2);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private AuditAnchorDocument exactAnchor(long position) {
        List<AuditAnchorDocument> found = anchors.findByPartitionKeyAndChainPositionBetween(
                PARTITION, position, position, 2);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private boolean validHead(AuditEventDocument head) {
        return head != null && head.chainPosition() != null && head.chainPosition() > 0
                && Objects.equals(head.partitionKey(), PARTITION) && validEvent(head)
                && validAnchor(exactAnchor(head.chainPosition()), head);
    }

    private HeadSnapshot stableHead() {
        for (int attempt = 0; attempt < 3; attempt++) {
            AuditEventDocument first = events.findLatestByPartitionKey(PARTITION).orElse(null);
            long count = events.countByPartitionKey(PARTITION);
            AuditEventDocument second = events.findLatestByPartitionKey(PARTITION).orElse(null);
            if (first == null && second == null && count == 0) return new HeadSnapshot(null, true);
            if (sameHead(first, second) && first != null && first.chainPosition() != null
                    && first.chainPosition() == count)
                return new HeadSnapshot(first, true);
        }
        return new HeadSnapshot(events.findLatestByPartitionKey(PARTITION).orElse(null), false);
    }

    private boolean sameHead(AuditEventDocument left, AuditEventDocument right) {
        return left != null && right != null && Objects.equals(left.chainPosition(), right.chainPosition())
                && Objects.equals(left.eventHash(), right.eventHash());
    }

    private boolean validEvent(AuditEventDocument event) {
        return event != null && Objects.equals(event.partitionKey(), PARTITION)
                && event.chainPosition() != null && event.chainPosition() > 0
                && "SHA-256".equals(event.hashAlgorithm()) && "1.0".equals(event.schemaVersion())
                && AuditEventHasher.matches(event);
    }

    private boolean validAnchor(AuditAnchorDocument anchor, AuditEventDocument event) {
        return anchor != null && event != null && anchor.chainPosition() == event.chainPosition()
                && Objects.equals(anchor.partitionKey(), PARTITION)
                && Objects.equals(anchor.lastEventHash(), event.eventHash())
                && Objects.equals(anchor.previousEventHash(), event.previousEventHash())
                && Objects.equals(anchor.hashAlgorithm(), event.hashAlgorithm());
    }

    private static void requireReader(PolicyGovernanceActor actor) {
        if (actor == null || !actor.authorities().contains(ModelPolicyAuthority.AUDIT_READ)
                || !actor.authorities().contains(ModelPolicyAuthority.PRODUCTION)) {
            throw new AccessDeniedException("Production governance audit mandate required");
        }
    }

    private static Result result(Status status, long start, long end, Long boundary, String boundaryHash,
            int checked, Long observedHead, boolean concurrentAppend, String reason) {
        return new Result(status, status == Status.CHAIN_RANGE_VERIFIED ? Dimension.VERIFIED : Dimension.NOT_CHECKED,
                Dimension.NOT_ASSESSED, Dimension.NOT_ASSESSED, Dimension.NOT_ASSESSED,
                start, end, boundary, boundaryHash, checked, observedHead, concurrentAppend, reason, null);
    }

    public enum Status { CHAIN_RANGE_VERIFIED, INVALID, TRUNCATED, RANGE_TOO_LARGE, INVALID_REQUEST, UNSTABLE_BOUNDARY }
    public enum Dimension { VERIFIED, NOT_CHECKED, NOT_ASSESSED }

    private record HeadSnapshot(AuditEventDocument head, boolean stable) { }

    public record Result(Status chainRangeIntegrity, Dimension eventIntegrity,
            Dimension historicalActionProof, Dimension externalWitness, Dimension currentAuthorization,
            long startPosition, long endPosition, Long boundaryPosition, String boundarySha256,
            int checkedEvents, Long observedHeadPosition, boolean concurrentAppendObserved, String reason,
            String terminalEventSha256) { }
}
