package com.frauddetection.enricher.graph;

import com.frauddetection.enricher.device.DeviceGraphReadGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationGrant;
import com.frauddetection.enricher.device.DeviceGraphRelationPort;
import com.frauddetection.enricher.device.DeviceGraphRelationResolution;
import com.frauddetection.enricher.device.VerifiedDeviceGraphRelation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Test-source-only assembly of the graph gate with the real Device owner port. */
public final class GraphEvidenceAdmissionTestFacade {
    private GraphEvidenceAdmissionTestFacade() { }

    public static List<GraphEdgeFact> admitAll(DeviceGraphRelationPort owner,
                                                DeviceGraphRelationGrant relationGrant,
                                                DeviceGraphReadGrant readGrant,
                                                List<String> ownerReferences,
                                                String requestingPrincipal,
                                                Instant deadline,
                                                Clock clock) {
        Map<String, GraphIdentityScope> resolvedScopes = new ConcurrentHashMap<>();
        GraphRelationAuthority authority = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return graphResolution(owner.admit(ownerReference, relationGrant), ownerReference, resolvedScopes);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                return graphResolution(owner.resolve(expected, relationGrant, readGrant, principal),
                        expected.ownerAssertionReference(), resolvedScopes);
            }
        };
        GraphPolicyAuthorizer policy = (principal, purpose, scope, requestDeadline, now) -> {
            if (!requestingPrincipal.equals(principal) || !GraphEvidenceAdmission.PURPOSE.equals(purpose)
                    || !requestDeadline.equals(deadline) || !deadline.isAfter(now)) {
                return GraphPolicyDecision.DENIED;
            }
            return scope == null || resolvedScopes.containsValue(scope)
                    ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED;
        };
        GraphEvidenceAdmission admission = new GraphEvidenceAdmission(authority, policy, clock);
        List<GraphAdmissionOutcome> outcomes = admission.admitBatch(ownerReferences.stream()
                .map(reference -> new GraphAdmissionRequest(reference, requestingPrincipal, deadline)).toList());
        if (outcomes.size() != ownerReferences.size()
                || outcomes.stream().anyMatch(outcome -> outcome.state() != GraphAdmissionState.ADMITTED)) {
            throw new AssertionError("GRAPH_OWNER_ADMISSION_DENIED");
        }
        return outcomes.stream().map(outcome -> outcome.relation().edge()).toList();
    }

    /** Runs the private Graph reader against the real Device/source owner port used by Testcontainers tests. */
    public static GraphReadOutcome readWithDeviceOwner(Supplier<DeviceGraphRelationPort> currentOwner,
                                                       DeviceGraphRelationGrant relationGrant,
                                                       DeviceGraphReadGrant deviceReadGrant,
                                                       GraphEdgeFact edge,
                                                       String requestingPrincipal,
                                                       Instant readAt,
                                                       Runnable afterFirstOwnerCheck) {
        GraphEvidenceRef evidence = edge.evidence();
        GraphStoredEdge stored = new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE,
                evidence.asOf(), evidence.expiresAt());
        AtomicInteger ownerChecks = new AtomicInteger();
        GraphRelationAuthority authority = new GraphRelationAuthority() {
            @Override public GraphRelationResolution resolveForAdmission(String ownerReference) {
                return GraphRelationResolution.unavailable(GraphReadReason.NOT_SUPPORTED);
            }

            @Override public GraphRelationResolution revalidate(GraphEvidenceRef expected, String principal) {
                if (!requestingPrincipal.equals(principal)) {
                    return GraphRelationResolution.denied(GraphReadReason.GRANT_DENIED);
                }
                DeviceGraphRelationResolution relation = currentOwner.get()
                        .resolve(expected, relationGrant, deviceReadGrant, principal);
                if (ownerChecks.incrementAndGet() == 1 && afterFirstOwnerCheck != null) {
                    afterFirstOwnerCheck.run();
                }
                return graphResolution(relation, expected.ownerAssertionReference(), new ConcurrentHashMap<>());
            }
        };
        GraphProjectionDao store = new GraphProjectionDao() {
            @Override public Optional<GraphStoredEdge> findLogicalRelation(GraphEdgeFact candidate) { return Optional.empty(); }
            @Override public List<GraphStoredEdge> findNeighbors(GraphNodeRef node, int limit) { return List.of(); }

            @Override public List<GraphStoredEdge> findOccurrenceNeighbors(GraphIdentityScope scope,
                                                                            GraphOccurrenceRef occurrence,
                                                                            Instant asOf, int limit,
                                                                            java.time.Duration maxTime) {
                if (stored.lifecycle() != GraphEdgeLifecycle.ACTIVE || !scope.equals(evidence.scope())
                        || !occurrence.equals(evidence.occurrence()) || !stored.admittedAt().isBefore(asOf)
                        || !stored.validUntil().isAfter(asOf)) {
                    return List.of();
                }
                return new ArrayList<>(List.of(stored));
            }
        };
        Instant grantExpiry = evidence.expiresAt().isBefore(readAt.plusSeconds(5))
                ? evidence.expiresAt() : readAt.plusSeconds(5);
        GraphPrivateReadGrant graphGrant = GraphPrivateReadGrant.testOnly(requestingPrincipal,
                evidence.scope(), 1, grantExpiry);
        GraphProjectionReader reader = new GraphProjectionReader(store, authority,
                (grant, principal, exactScope, now) -> grant.matches(principal, exactScope, now)
                        ? GraphPolicyDecision.AUTHORIZED : GraphPolicyDecision.DENIED,
                GraphQueryLimits.DEFAULT, new GraphReadCursorCodec(new byte[32]), Clock.fixed(readAt, ZoneOffset.UTC));
        Instant until = readAt.isAfter(evidence.occurrence().eventTime()) ? readAt : readAt.plusMillis(1);
        return reader.readAuthorizedOccurrenceNeighbors(requestingPrincipal, graphGrant, evidence.scope(),
                evidence.occurrence(), evidence.occurrence().eventTime().minusMillis(1), until,
                GraphQueryLimits.DEFAULT.maxPageSize(), null);
    }

    private static GraphRelationResolution graphResolution(DeviceGraphRelationResolution result, String reference,
                                                           Map<String, GraphIdentityScope> scopes) {
        if (result == null) { return GraphRelationResolution.unavailable(GraphReadReason.OWNER_UNAVAILABLE); }
        return switch (result.state()) {
            case VERIFIED -> {
                VerifiedDeviceGraphRelation relation = result.relation().orElseThrow();
                if (!reference.equals(relation.reference().ownerAssertionReference())) {
                    yield GraphRelationResolution.denied(GraphReadReason.REFERENCE_INVALID);
                }
                scopes.put(reference, relation.reference().scope());
                yield GraphRelationResolution.verified(relation.reference(), relation.validUntil());
            }
            case PENDING -> GraphRelationResolution.pending(result.reason());
            case UNAVAILABLE -> GraphRelationResolution.unavailable(result.reason());
            case DENIED -> GraphRelationResolution.denied(result.reason());
            case CONFLICT -> GraphRelationResolution.conflict();
        };
    }
}
