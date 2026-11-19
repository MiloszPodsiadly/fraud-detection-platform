package com.frauddetection.enricher.graph;

import com.mongodb.MongoException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Internal, read-only and deliberately unwired bounded Graph projection reader. */
final class GraphProjectionReader {
    private final GraphProjectionDao store;
    private final GraphRelationAuthority ownerAuthority;
    private final GraphPrivateReadGrantVerifier grantVerifier;
    private final GraphQueryLimits limits;
    private final GraphReadCursorCodec cursors;
    private final Clock clock;
    private final LongSupplier nanoTime;

    GraphProjectionReader(GraphProjectionDao store, GraphRelationAuthority ownerAuthority,
                          GraphPrivateReadGrantVerifier grantVerifier, GraphQueryLimits limits,
                          GraphReadCursorCodec cursors, Clock clock) {
        this(store, ownerAuthority, grantVerifier, limits, cursors, clock, System::nanoTime);
    }

    GraphProjectionReader(GraphProjectionDao store, GraphRelationAuthority ownerAuthority,
                          GraphPrivateReadGrantVerifier grantVerifier, GraphQueryLimits limits,
                          GraphReadCursorCodec cursors, Clock clock, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store, "GRAPH_STORE_REQUIRED");
        this.ownerAuthority = Objects.requireNonNull(ownerAuthority, "GRAPH_OWNER_AUTHORITY_REQUIRED");
        this.grantVerifier = Objects.requireNonNull(grantVerifier, "GRAPH_READ_GRANT_VERIFIER_REQUIRED");
        this.limits = Objects.requireNonNull(limits, "GRAPH_QUERY_LIMITS_REQUIRED");
        this.cursors = Objects.requireNonNull(cursors, "GRAPH_CURSOR_CODEC_REQUIRED");
        this.clock = Objects.requireNonNull(clock, "GRAPH_CLOCK_REQUIRED");
        this.nanoTime = Objects.requireNonNull(nanoTime, "GRAPH_NANO_TIME_REQUIRED");
    }

    GraphReadOutcome readAuthorizedOccurrenceNeighbors(String requestingPrincipal,
                                                        GraphPrivateReadGrant grant,
                                                        GraphIdentityScope exactScope,
                                                        GraphOccurrenceRef typedRoot,
                                                        Instant fromInclusive,
                                                        Instant untilExclusive,
                                                        int requestedPageSize,
                                                        String cursor) {
        long started = nanoTime.getAsLong();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (grant == null || exactScope == null || typedRoot == null || requestingPrincipal == null
                || !grant.matches(requestingPrincipal, exactScope, now)) {
            return GraphReadOutcome.denied(GraphReadReason.GRANT_DENIED);
        }
        if (!exactScope.equals(typedRoot.scope())) {
            return GraphReadOutcome.denied(GraphReadReason.GRANT_DENIED);
        }
        if (!validRange(fromInclusive, untilExclusive, now) || typedRoot.eventTime().isBefore(fromInclusive)
                || !typedRoot.eventTime().isBefore(untilExclusive) || typedRoot.eventTime().isAfter(now)
                || requestedPageSize < 1
                || requestedPageSize > limits.maxPageSize()) {
            return GraphReadOutcome.unavailable(GraphReadReason.LIMIT_EXCEEDED);
        }
        GraphPolicyDecision grantState = verifyGrant(grant, requestingPrincipal, exactScope, now);
        if (grantState != GraphPolicyDecision.AUTHORIZED) { return grantOutcome(grantState); }

        GraphReadCursorCodec.Claims page = null;
        Instant asOf = now;
        if (cursor != null) {
            try {
                page = cursors.decode(cursor);
            } catch (IllegalArgumentException invalidCursor) {
                return GraphReadOutcome.denied(GraphReadReason.REFERENCE_INVALID);
            }
            if (!cursorMatches(page, grant, exactScope, typedRoot, fromInclusive, untilExclusive, now)) {
                return GraphReadOutcome.denied(GraphReadReason.REFERENCE_INVALID);
            }
            asOf = Instant.ofEpochMilli(page.asOfMillis());
        }

        List<GraphStoredEdge> stored;
        try {
            stored = store.findOccurrenceNeighbors(exactScope, typedRoot, asOf,
                    limits.maxEdges() + 1, limits.maxQueryTime());
        } catch (MongoException | IllegalStateException unavailable) {
            return GraphReadOutcome.unavailable(readFailure(started));
        } catch (IllegalArgumentException invalid) {
            return GraphReadOutcome.unavailable(GraphReadReason.REFERENCE_INVALID);
        }
        if (expired(started)) { return GraphReadOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }
        if (stored.size() > limits.maxEdges()) {
            return GraphReadOutcome.unavailable(GraphReadReason.TRUNCATED_UNVERIFIED);
        }
        if (stored.isEmpty()) {
            return GraphReadOutcome.unavailable(GraphReadReason.COVERAGE_UNAVAILABLE);
        }

        String datasetHash;
        List<GraphEdgeFact> verified = new ArrayList<>(stored.size());
        Set<String> nodes = new java.util.HashSet<>();
        GraphNodeRef rootNode = GraphNodeRef.occurrence(typedRoot);
        nodes.add(rootNode.canonicalId());
        for (GraphStoredEdge row : stored) {
            if (expired(started)) { return GraphReadOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }
            GraphEdgeFact edge = row.edge();
            GraphEvidenceRef evidence = edge.evidence();
            if (row.lifecycle() != GraphEdgeLifecycle.ACTIVE
                    || !edge.from().equals(rootNode) || !evidence.occurrence().equals(typedRoot)
                    || !evidence.scope().equals(exactScope)
                    || evidence.occurrence().eventTime().isAfter(asOf)
                    || !row.validUntil().isAfter(asOf) || !evidence.expiresAt().isAfter(asOf)) {
                return GraphReadOutcome.unavailable(GraphReadReason.REFERENCE_INVALID);
            }
            GraphRelationResolution owner;
            try {
                owner = ownerAuthority.revalidate(evidence, requestingPrincipal);
            } catch (RuntimeException ownerUnavailable) {
                return GraphReadOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
            }
            if (owner == null || owner.state() != GraphRelationResolution.State.VERIFIED) {
                return ownerOutcome(owner);
            }
            if (!evidence.equals(owner.evidence())) {
                return GraphReadOutcome.conflict();
            }
            Instant verifiedAt = clock.instant();
            if (!row.validUntil().isAfter(verifiedAt) || !owner.validUntil().isAfter(verifiedAt)
                    || !evidence.expiresAt().isAfter(verifiedAt)) {
                return GraphReadOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
            }
            nodes.add(edge.to().canonicalId());
            if (nodes.size() > limits.maxNodes()) {
                return GraphReadOutcome.unavailable(GraphReadReason.TRUNCATED_UNVERIFIED);
            }
            verified.add(edge);
        }
        if (expired(started)) { return GraphReadOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }

        List<GraphStoredEdge> postOwnerSnapshot;
        try {
            postOwnerSnapshot = store.findOccurrenceNeighbors(exactScope, typedRoot, asOf,
                    limits.maxEdges() + 1, remainingBudget(started));
        } catch (MongoException | IllegalStateException unavailable) {
            return GraphReadOutcome.unavailable(readFailure(started));
        } catch (IllegalArgumentException invalid) {
            return GraphReadOutcome.unavailable(GraphReadReason.REFERENCE_INVALID);
        }
        if (expired(started) || !stored.equals(postOwnerSnapshot)) {
            return GraphReadOutcome.unavailable(expired(started)
                    ? GraphReadReason.DEADLINE_EXCEEDED : GraphReadReason.TRUNCATED_UNVERIFIED);
        }

        for (GraphStoredEdge row : stored) {
            if (expired(started)) { return GraphReadOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED); }
            GraphReadOutcome ownerFailure = revalidateOwner(row, requestingPrincipal);
            if (ownerFailure != null) { return ownerFailure; }
        }
        List<GraphStoredEdge> finalGraphSnapshot;
        try {
            finalGraphSnapshot = store.findOccurrenceNeighbors(exactScope, typedRoot, asOf,
                    limits.maxEdges() + 1, remainingBudget(started));
        } catch (MongoException | IllegalStateException unavailable) {
            return GraphReadOutcome.unavailable(readFailure(started));
        } catch (IllegalArgumentException invalid) {
            return GraphReadOutcome.unavailable(GraphReadReason.REFERENCE_INVALID);
        }
        if (expired(started) || !stored.equals(finalGraphSnapshot)) {
            return GraphReadOutcome.unavailable(expired(started)
                    ? GraphReadReason.DEADLINE_EXCEEDED : GraphReadReason.TRUNCATED_UNVERIFIED);
        }
        GraphPolicyDecision finalGrant = verifyGrant(grant, requestingPrincipal, exactScope, clock.instant());
        if (!grant.matches(requestingPrincipal, exactScope, clock.instant())
                || finalGrant != GraphPolicyDecision.AUTHORIZED) {
            return grantOutcome(finalGrant == GraphPolicyDecision.AUTHORIZED
                    ? GraphPolicyDecision.DENIED : finalGrant);
        }

        datasetHash = datasetHash(stored);
        if (page != null && !page.datasetHash().equals(datasetHash)) {
            return GraphReadOutcome.unavailable(GraphReadReason.TRUNCATED_UNVERIFIED);
        }
        int start = 0;
        if (page != null) {
            int last = -1;
            for (int index = 0; index < stored.size(); index++) {
                if (stored.get(index).edge().canonicalId().equals(page.lastEdgeId())) { last = index; break; }
            }
            if (last < 0) { return GraphReadOutcome.unavailable(GraphReadReason.TRUNCATED_UNVERIFIED); }
            start = last + 1;
        }
        if (start >= verified.size()) { return GraphReadOutcome.unavailable(GraphReadReason.REFERENCE_INVALID); }
        int end = Math.min(start + requestedPageSize, verified.size());
        String next = null;
        if (end < verified.size()) {
            next = cursors.encode(new GraphReadCursorCodec.Claims(scopeHash(exactScope),
                    GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name(), rootId(typedRoot),
                    fromInclusive.toEpochMilli(), untilExclusive.toEpochMilli(), asOf.toEpochMilli(),
                    grant.policyVersion(), stored.get(end - 1).edge().canonicalId(), datasetHash));
        }
        return GraphReadOutcome.verifiedBounded(GraphOwnerResolution.afterOwnerRevalidation(),
                verified.subList(start, end), next);
    }

    private GraphPolicyDecision verifyGrant(GraphPrivateReadGrant grant, String principal,
                                           GraphIdentityScope scope, Instant now) {
        try {
            return grantVerifier.verify(grant, principal, scope, now);
        } catch (RuntimeException unavailable) {
            return GraphPolicyDecision.UNAVAILABLE;
        }
    }

    private boolean validRange(Instant from, Instant until, Instant now) {
        if (from == null || until == null) { return false; }
        try {
            GraphValidation.millisecondInstant(from);
            GraphValidation.millisecondInstant(until);
            return !from.isBefore(Instant.EPOCH) && until.isAfter(from) && !until.isAfter(now)
                    && Duration.between(from, until).compareTo(limits.maxWindow()) <= 0;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean cursorMatches(GraphReadCursorCodec.Claims page, GraphPrivateReadGrant grant,
                                  GraphIdentityScope scope, GraphOccurrenceRef root, Instant from,
                                  Instant until, Instant now) {
        return page.scopeHash().equals(scopeHash(scope))
                && page.edgeType().equals(GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())
                && page.rootId().equals(rootId(root))
                && page.fromMillis() == from.toEpochMilli() && page.untilMillis() == until.toEpochMilli()
                && page.policyVersion() == grant.policyVersion() && page.asOfMillis() <= now.toEpochMilli()
                && Duration.between(Instant.ofEpochMilli(page.asOfMillis()), now).compareTo(limits.maxQueryTime()) <= 0;
    }

    private boolean expired(long started) {
        return nanoTime.getAsLong() - started > limits.maxQueryTime().toNanos();
    }

    private Duration remainingBudget(long started) {
        long remaining = limits.maxQueryTime().toNanos() - (nanoTime.getAsLong() - started);
        if (remaining <= 0) { throw new IllegalStateException("GRAPH_READ_DEADLINE_EXCEEDED"); }
        return Duration.ofNanos(remaining);
    }

    private GraphReadReason readFailure(long started) {
        return expired(started) ? GraphReadReason.DEADLINE_EXCEEDED : GraphReadReason.SOURCE_UNAVAILABLE;
    }

    private GraphReadOutcome revalidateOwner(GraphStoredEdge row, String requestingPrincipal) {
        GraphRelationResolution current;
        try {
            current = ownerAuthority.revalidate(row.edge().evidence(), requestingPrincipal);
        } catch (RuntimeException unavailable) {
            return GraphReadOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
        }
        if (current == null || current.state() != GraphRelationResolution.State.VERIFIED) {
            return ownerOutcome(current);
        }
        if (!row.edge().evidence().equals(current.evidence())) { return GraphReadOutcome.conflict(); }
        Instant now = clock.instant();
        if (!row.validUntil().isAfter(now) || !current.validUntil().isAfter(now)
                || !row.edge().evidence().expiresAt().isAfter(now)) {
            return GraphReadOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        return null;
    }

    private static String scopeHash(GraphIdentityScope scope) {
        return GraphCanonical.digest("graph-read-scope/v1", scope.sourceIssuerToken(), scope.institutionToken(),
                scope.sourceToken(), scope.sourceEpochToken(), Integer.toString(scope.identityVersion()));
    }

    private static String rootId(GraphOccurrenceRef occurrence) {
        return GraphCanonical.digest("graph-read-occurrence-root/v1", scopeHash(occurrence.scope()),
                occurrence.occurrenceToken(), Long.toString(occurrence.eventTime().toEpochMilli()));
    }

    private static String datasetHash(List<GraphStoredEdge> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (GraphStoredEdge row : rows) {
                update(digest, row.edge().canonicalId());
                update(digest, row.lifecycle().name());
                update(digest, Long.toString(row.admittedAt().toEpochMilli()));
                update(digest, Long.toString(row.validUntil().toEpochMilli()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) {
            throw new IllegalStateException("GRAPH_READ_FINGERPRINT_UNAVAILABLE");
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static GraphReadOutcome grantOutcome(GraphPolicyDecision decision) {
        return decision == GraphPolicyDecision.DENIED
                ? GraphReadOutcome.denied(GraphReadReason.GRANT_DENIED)
                : GraphReadOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
    }

    private static GraphReadOutcome ownerOutcome(GraphRelationResolution owner) {
        if (owner == null) { return GraphReadOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        return switch (owner.state()) {
            case VERIFIED -> throw new IllegalArgumentException("GRAPH_OWNER_OUTCOME_INVALID");
            case PENDING -> GraphReadOutcome.pending(owner.reason());
            case UNAVAILABLE -> GraphReadOutcome.unavailable(owner.reason());
            case DENIED -> GraphReadOutcome.denied(owner.reason());
            case CONFLICT -> GraphReadOutcome.conflict();
        };
    }
}
