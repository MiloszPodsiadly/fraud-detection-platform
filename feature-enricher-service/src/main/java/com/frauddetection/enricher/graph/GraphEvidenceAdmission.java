package com.frauddetection.enricher.graph;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Sole graph-layer edge admission path. No runtime bean or production grant issuer registers it. */
final class GraphEvidenceAdmission {
    static final String PURPOSE = "GRAPH_RELATION_PROJECTION";
    static final int MAX_BATCH = GraphPolicyLimits.HARD_MAX_EDGES;
    private static final Duration MAX_REQUEST_WINDOW = Duration.ofSeconds(5);
    private static final long MAX_ELAPSED_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final GraphRelationAuthority relationAuthority;
    private final GraphPolicyAuthorizer policyAuthorizer;
    private final Clock clock;
    private final LongSupplier nanoTime;

    GraphEvidenceAdmission(GraphRelationAuthority relationAuthority, GraphPolicyAuthorizer policyAuthorizer,
                           Clock clock) {
        this(relationAuthority, policyAuthorizer, clock, System::nanoTime);
    }

    GraphEvidenceAdmission(GraphRelationAuthority relationAuthority, GraphPolicyAuthorizer policyAuthorizer,
                           Clock clock, LongSupplier nanoTime) {
        this.relationAuthority = Objects.requireNonNull(relationAuthority, "GRAPH_RELATION_AUTHORITY_REQUIRED");
        this.policyAuthorizer = Objects.requireNonNull(policyAuthorizer, "GRAPH_POLICY_AUTHORIZER_REQUIRED");
        this.clock = Objects.requireNonNull(clock, "GRAPH_CLOCK_REQUIRED");
        this.nanoTime = Objects.requireNonNull(nanoTime, "GRAPH_NANO_TIME_REQUIRED");
    }

    GraphAdmissionOutcome admit(GraphAdmissionRequest request) {
        if (request == null) { return GraphAdmissionOutcome.denied(GraphReadReason.REFERENCE_INVALID); }
        long started = nanoTime.getAsLong();
        Instant now = clock.instant();
        GraphReadReason initialDeadline = deadlineState(request.deadline(), now, started);
        if (initialDeadline != null) { return deadlineOutcome(initialDeadline); }

        GraphPolicyDecision preflight = policyAuthorizer.authorize(request.requestingPrincipal(), PURPOSE,
                null, request.deadline(), now);
        if (preflight != GraphPolicyDecision.AUTHORIZED) { return policyOutcome(preflight); }

        GraphRelationResolution initial = relationAuthority.resolveForAdmission(request.ownerReference());
        if (initial == null) { return GraphAdmissionOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        if (initial.state() != GraphRelationResolution.State.VERIFIED) { return ownerOutcome(initial); }
        GraphEvidenceRef evidence = initial.evidence();
        if (!request.ownerReference().equals(evidence.ownerAssertionReference())) {
            return GraphAdmissionOutcome.denied(GraphReadReason.REFERENCE_INVALID);
        }
        if (!evidence.expiresAt().isAfter(clock.instant()) || !initial.validUntil().isAfter(clock.instant())) {
            return GraphAdmissionOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }

        GraphPolicyDecision scoped = policyAuthorizer.authorize(request.requestingPrincipal(), PURPOSE,
                evidence.scope(), request.deadline(), clock.instant());
        if (scoped != GraphPolicyDecision.AUTHORIZED) { return policyOutcome(scoped); }
        GraphEdgeFact candidate = edge(evidence);
        GraphReadReason beforeFenceDeadline = deadlineState(request.deadline(), clock.instant(), started);
        if (beforeFenceDeadline != null) { return deadlineOutcome(beforeFenceDeadline); }

        GraphRelationResolution current = relationAuthority.revalidate(evidence, request.requestingPrincipal());
        if (current == null) { return GraphAdmissionOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        if (current.state() != GraphRelationResolution.State.VERIFIED) { return ownerOutcome(current); }
        if (!evidence.equals(current.evidence())) { return GraphAdmissionOutcome.conflict(); }

        GraphPolicyDecision finalPolicy = policyAuthorizer.authorize(request.requestingPrincipal(), PURPOSE,
                evidence.scope(), request.deadline(), clock.instant());
        if (finalPolicy != GraphPolicyDecision.AUTHORIZED) { return policyOutcome(finalPolicy); }
        Instant finishedAt = clock.instant();
        GraphReadReason finishedDeadline = deadlineState(request.deadline(), finishedAt, started);
        if (finishedDeadline != null) { return deadlineOutcome(finishedDeadline); }
        Instant validUntil = minimum(evidence.expiresAt(), initial.validUntil(), current.validUntil());
        if (!validUntil.isAfter(finishedAt)) {
            return GraphAdmissionOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        return GraphAdmissionOutcome.admitted(new AdmittedRelation(candidate, validUntil, request.deadline(),
                request.requestingPrincipal()));
    }

    GraphAdmissionOutcome revalidateForPersistence(AdmittedRelation admitted) {
        if (admitted == null) { return GraphAdmissionOutcome.denied(GraphReadReason.REFERENCE_INVALID); }
        GraphEvidenceRef evidence = admitted.edge().evidence();
        long started = nanoTime.getAsLong();
        Instant startedAt = clock.instant();
        GraphReadReason deadline = deadlineState(admitted.operationDeadline(), startedAt, started);
        if (deadline != null) { return deadlineOutcome(deadline); }
        if (!admitted.validUntil().isAfter(startedAt)) {
            return GraphAdmissionOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        GraphPolicyDecision before = policyAuthorizer.authorize(admitted.requestingPrincipal(), PURPOSE,
                evidence.scope(), admitted.operationDeadline(), startedAt);
        if (before != GraphPolicyDecision.AUTHORIZED) { return policyOutcome(before); }
        deadline = deadlineState(admitted.operationDeadline(), clock.instant(), started);
        if (deadline != null) { return deadlineOutcome(deadline); }
        GraphRelationResolution current = relationAuthority.revalidate(evidence, admitted.requestingPrincipal());
        if (current == null) { return GraphAdmissionOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        if (current.state() != GraphRelationResolution.State.VERIFIED) { return ownerOutcome(current); }
        if (!evidence.equals(current.evidence())) { return GraphAdmissionOutcome.conflict(); }
        deadline = deadlineState(admitted.operationDeadline(), clock.instant(), started);
        if (deadline != null) { return deadlineOutcome(deadline); }
        GraphPolicyDecision after = policyAuthorizer.authorize(admitted.requestingPrincipal(), PURPOSE,
                evidence.scope(), admitted.operationDeadline(), clock.instant());
        if (after != GraphPolicyDecision.AUTHORIZED) { return policyOutcome(after); }
        Instant now = clock.instant();
        deadline = deadlineState(admitted.operationDeadline(), now, started);
        if (deadline != null) { return deadlineOutcome(deadline); }
        Instant validUntil = current.validUntil().isBefore(admitted.validUntil())
                ? current.validUntil() : admitted.validUntil();
        if (!validUntil.isAfter(now)) {
            return GraphAdmissionOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        return GraphAdmissionOutcome.admitted(new AdmittedRelation(admitted.edge(), validUntil,
                admitted.operationDeadline(), admitted.requestingPrincipal()));
    }

    GraphLifecycleFenceOutcome resolveLifecycleFence(AdmittedRelation admitted) {
        if (admitted == null) { return GraphLifecycleFenceOutcome.denied(GraphReadReason.REFERENCE_INVALID); }
        long started = nanoTime.getAsLong();
        Instant now = clock.instant();
        GraphReadReason deadline = deadlineState(admitted.operationDeadline(), now, started);
        if (deadline != null) { return GraphLifecycleFenceOutcome.unavailable(deadline); }
        if (!admitted.validUntil().isAfter(now)) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        GraphRelationResolution current = relationAuthority.revalidate(admitted.edge().evidence(), admitted.requestingPrincipal());
        if (current == null) { return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        now = clock.instant();
        deadline = deadlineState(admitted.operationDeadline(), now, started);
        if (deadline != null) { return GraphLifecycleFenceOutcome.unavailable(deadline); }
        if (!admitted.validUntil().isAfter(now)
                || current.state() == GraphRelationResolution.State.VERIFIED && !current.validUntil().isAfter(now)) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }
        if (current.state() == GraphRelationResolution.State.VERIFIED) {
            return evidenceMatches(admitted, current.evidence())
                    ? GraphLifecycleFenceOutcome.noChange()
                    : GraphLifecycleFenceOutcome.fence(new LifecycleFence(admitted.edge(), GraphEdgeLifecycle.CONFLICT));
        }
        if (current.state() == GraphRelationResolution.State.CONFLICT) {
            return GraphLifecycleFenceOutcome.fence(new LifecycleFence(admitted.edge(), GraphEdgeLifecycle.CONFLICT));
        }
        if (current.state() == GraphRelationResolution.State.DENIED
                && current.reason() == GraphReadReason.AUTHORITY_REVOKED) {
            return GraphLifecycleFenceOutcome.fence(new LifecycleFence(admitted.edge(), GraphEdgeLifecycle.REVOKED));
        }
        return switch (current.state()) {
            case VERIFIED, CONFLICT -> throw new IllegalArgumentException("GRAPH_FENCE_OWNER_OUTCOME_INVALID");
            case PENDING -> GraphLifecycleFenceOutcome.pending(current.reason());
            case UNAVAILABLE -> GraphLifecycleFenceOutcome.unavailable(current.reason());
            case DENIED -> GraphLifecycleFenceOutcome.denied(current.reason());
        };
    }

    /** Reconciliation has its own bounded authorization and never reuses an expired admission handle. */
    GraphLifecycleFenceOutcome resolveReconciliationFence(GraphLifecycleReconciliationRequest request,
                                                            GraphStoredEdge stored) {
        if (request == null || stored == null) {
            return GraphLifecycleFenceOutcome.denied(GraphReadReason.REFERENCE_INVALID);
        }
        GraphEdgeFact edge = stored.edge();
        GraphEvidenceRef evidence = edge.evidence();
        if (!request.scope().equals(evidence.scope())
                || !request.occurrence().equals(evidence.occurrence())
                || !request.associatedIdentityToken().equals(evidence.associatedIdentityToken())
                || request.pseudonymKeyVersion() != evidence.pseudonymKeyVersion()) {
            return GraphLifecycleFenceOutcome.denied(GraphReadReason.REFERENCE_INVALID);
        }

        long started = nanoTime.getAsLong();
        Instant now = clock.instant();
        GraphReadReason deadline = deadlineState(request.operationDeadline(), now, started);
        if (deadline != null) { return reconciliationDeadlineOutcome(deadline); }
        if (!evidence.expiresAt().isAfter(now)) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
        }

        GraphPolicyDecision before;
        try {
            before = policyAuthorizer.authorize(request.requestingPrincipal(),
                    GraphLifecycleReconciliationRequest.PURPOSE, request.scope(), request.operationDeadline(), now);
        } catch (RuntimeException unavailable) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
        }
        if (before != GraphPolicyDecision.AUTHORIZED) { return reconciliationPolicyOutcome(before); }

        deadline = deadlineState(request.operationDeadline(), clock.instant(), started);
        if (deadline != null) { return reconciliationDeadlineOutcome(deadline); }
        GraphRelationResolution current;
        try {
            current = relationAuthority.revalidate(evidence, request.requestingPrincipal());
        } catch (RuntimeException unavailable) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
        }
        if (current == null) { return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }

        GraphLifecycleFenceOutcome decision = switch (current.state()) {
            case VERIFIED -> {
                if (!evidence.equals(current.evidence())) {
                    yield GraphLifecycleFenceOutcome.unavailable(GraphReadReason.OWNER_UNAVAILABLE);
                }
                if (!current.validUntil().isAfter(clock.instant())) {
                    yield GraphLifecycleFenceOutcome.unavailable(GraphReadReason.RETENTION_EXPIRED);
                }
                yield GraphLifecycleFenceOutcome.noChange();
            }
            case CONFLICT -> GraphLifecycleFenceOutcome.fence(new LifecycleFence(edge, GraphEdgeLifecycle.CONFLICT));
            case DENIED -> current.reason() == GraphReadReason.AUTHORITY_REVOKED
                    ? GraphLifecycleFenceOutcome.fence(new LifecycleFence(edge, GraphEdgeLifecycle.REVOKED))
                    : GraphLifecycleFenceOutcome.denied(current.reason());
            case PENDING -> GraphLifecycleFenceOutcome.pending(current.reason());
            case UNAVAILABLE -> GraphLifecycleFenceOutcome.unavailable(current.reason());
        };

        deadline = deadlineState(request.operationDeadline(), clock.instant(), started);
        if (deadline != null) { return reconciliationDeadlineOutcome(deadline); }
        GraphPolicyDecision after;
        try {
            after = policyAuthorizer.authorize(request.requestingPrincipal(),
                    GraphLifecycleReconciliationRequest.PURPOSE, request.scope(), request.operationDeadline(), clock.instant());
        } catch (RuntimeException unavailable) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
        }
        if (after != GraphPolicyDecision.AUTHORIZED) { return reconciliationPolicyOutcome(after); }
        deadline = deadlineState(request.operationDeadline(), clock.instant(), started);
        return deadline == null ? decision : reconciliationDeadlineOutcome(deadline);
    }

    /** Policy and operation fencing happen before even the exact-key persistence read. */
    GraphLifecycleFenceOutcome preflightReconciliation(GraphLifecycleReconciliationRequest request) {
        if (request == null) { return GraphLifecycleFenceOutcome.denied(GraphReadReason.REFERENCE_INVALID); }
        long started = nanoTime.getAsLong();
        Instant now = clock.instant();
        GraphReadReason deadline = deadlineState(request.operationDeadline(), now, started);
        if (deadline != null) { return reconciliationDeadlineOutcome(deadline); }
        GraphPolicyDecision policy;
        try {
            policy = policyAuthorizer.authorize(request.requestingPrincipal(),
                    GraphLifecycleReconciliationRequest.PURPOSE, request.scope(), request.operationDeadline(), now);
        } catch (RuntimeException unavailable) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
        }
        if (policy != GraphPolicyDecision.AUTHORIZED) { return reconciliationPolicyOutcome(policy); }
        deadline = deadlineState(request.operationDeadline(), clock.instant(), started);
        return deadline == null ? GraphLifecycleFenceOutcome.noChange() : reconciliationDeadlineOutcome(deadline);
    }

    private static GraphLifecycleFenceOutcome reconciliationDeadlineOutcome(GraphReadReason reason) {
        return reason == GraphReadReason.LIMIT_EXCEEDED
                ? GraphLifecycleFenceOutcome.denied(reason)
                : GraphLifecycleFenceOutcome.unavailable(reason);
    }

    private static GraphLifecycleFenceOutcome reconciliationPolicyOutcome(GraphPolicyDecision decision) {
        if (decision == null || decision == GraphPolicyDecision.UNAVAILABLE) {
            return GraphLifecycleFenceOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
        }
        if (decision == GraphPolicyDecision.DENIED) {
            return GraphLifecycleFenceOutcome.denied(GraphReadReason.GRANT_DENIED);
        }
        throw new IllegalArgumentException("GRAPH_RECONCILIATION_POLICY_OUTCOME_INVALID");
    }

    private static boolean evidenceMatches(AdmittedRelation admitted, GraphEvidenceRef current) {
        return admitted.edge().evidence().equals(current);
    }

    List<GraphAdmissionOutcome> admitBatch(List<GraphAdmissionRequest> requests) {
        if (requests == null || requests.size() > MAX_BATCH) {
            return List.of(GraphAdmissionOutcome.denied(GraphReadReason.LIMIT_EXCEEDED));
        }
        List<GraphAdmissionOutcome> results = new ArrayList<>(requests.size());
        long batchStarted = nanoTime.getAsLong();
        for (int index = 0; index < requests.size(); index++) {
            if (nanoTime.getAsLong() - batchStarted > MAX_ELAPSED_NANOS) {
                while (index++ < requests.size()) {
                    results.add(GraphAdmissionOutcome.unavailable(GraphReadReason.DEADLINE_EXCEEDED));
                }
                break;
            }
            results.add(admit(requests.get(index)));
        }
        return List.copyOf(results);
    }

    private GraphAdmissionOutcome policyOutcome(GraphPolicyDecision decision) {
        return switch (decision) {
            case AUTHORIZED -> throw new IllegalArgumentException("GRAPH_POLICY_OUTCOME_INVALID");
            case DENIED -> GraphAdmissionOutcome.denied(GraphReadReason.GRANT_DENIED);
            case UNAVAILABLE -> GraphAdmissionOutcome.unavailable(GraphReadReason.POLICY_UNAVAILABLE);
        };
    }

    private static GraphAdmissionOutcome ownerOutcome(GraphRelationResolution result) {
        return switch (result.state()) {
            case VERIFIED -> throw new IllegalArgumentException("GRAPH_OWNER_OUTCOME_INVALID");
            case PENDING -> GraphAdmissionOutcome.pending(result.reason());
            case UNAVAILABLE -> GraphAdmissionOutcome.unavailable(result.reason());
            case DENIED -> GraphAdmissionOutcome.denied(result.reason());
            case CONFLICT -> GraphAdmissionOutcome.conflict();
        };
    }

    private static GraphEdgeFact edge(GraphEvidenceRef evidence) {
        return new GraphEdgeFact(GraphProjectionVersion.CURRENT, GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(evidence.occurrence()),
                GraphNodeRef.device(evidence.scope(), evidence.associatedIdentityToken(), evidence.pseudonymKeyVersion()),
                evidence);
    }

    private GraphReadReason deadlineState(Instant deadline, Instant now, long started) {
        if (Duration.between(now, deadline).compareTo(MAX_REQUEST_WINDOW) > 0) { return GraphReadReason.LIMIT_EXCEEDED; }
        if (!deadline.isAfter(now) || nanoTime.getAsLong() - started > MAX_ELAPSED_NANOS) {
            return GraphReadReason.DEADLINE_EXCEEDED;
        }
        return null;
    }

    private static GraphAdmissionOutcome deadlineOutcome(GraphReadReason reason) {
        return reason == GraphReadReason.LIMIT_EXCEEDED
                ? GraphAdmissionOutcome.denied(reason)
                : GraphAdmissionOutcome.unavailable(reason);
    }

    private static Instant minimum(Instant... instants) {
        Instant earliest = instants[0];
        for (int index = 1; index < instants.length; index++) {
            if (instants[index].isBefore(earliest)) { earliest = instants[index]; }
        }
        return earliest;
    }

    /** Construction is private to admission; persistence cannot accept a caller-created edge as verified. */
    static final class AdmittedRelation {
        private final GraphEdgeFact edge;
        private final Instant validUntil;
        private final Instant operationDeadline;
        private final String requestingPrincipal;

        private AdmittedRelation(GraphEdgeFact edge, Instant validUntil, Instant operationDeadline,
                                 String requestingPrincipal) {
            this.edge = Objects.requireNonNull(edge, "GRAPH_EDGE_REQUIRED");
            this.validUntil = Objects.requireNonNull(validUntil, "GRAPH_VALID_UNTIL_REQUIRED");
            this.operationDeadline = Objects.requireNonNull(operationDeadline, "GRAPH_OPERATION_DEADLINE_REQUIRED");
            this.requestingPrincipal = Objects.requireNonNull(requestingPrincipal, "GRAPH_PRINCIPAL_REQUIRED");
            GraphValidation.millisecondInstant(operationDeadline);
            if (!validUntil.isAfter(edge.evidence().asOf()) || validUntil.isAfter(edge.evidence().expiresAt())) {
                throw new IllegalArgumentException("GRAPH_ADMITTED_RELATION_EXPIRY_INVALID");
            }
        }

        GraphEdgeFact edge() { return edge; }
        Instant validUntil() { return validUntil; }
        Instant operationDeadline() { return operationDeadline; }
        String requestingPrincipal() { return requestingPrincipal; }

        @Override public String toString() { return "GraphAdmittedRelation[redacted]"; }
    }

    /** Owner resolution is required before a lifecycle fence can be issued. */
    static final class LifecycleFence {
        private final GraphEdgeFact edge;
        private final GraphEdgeLifecycle lifecycle;

        private LifecycleFence(GraphEdgeFact edge, GraphEdgeLifecycle lifecycle) {
            this.edge = Objects.requireNonNull(edge, "GRAPH_EDGE_REQUIRED");
            if (lifecycle != GraphEdgeLifecycle.CONFLICT && lifecycle != GraphEdgeLifecycle.REVOKED) {
                throw new IllegalArgumentException("GRAPH_FENCE_LIFECYCLE_INVALID");
            }
            this.lifecycle = lifecycle;
        }

        GraphEdgeFact edge() { return edge; }
        GraphEdgeLifecycle lifecycle() { return lifecycle; }

        @Override public String toString() { return "GraphLifecycleFence[" + lifecycle + ",redacted]"; }
    }
}
