package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Private typed result for a current, bounded two-hop observation. */
final class GraphDeviceWitnessOutcome {
    private final GraphProjectionState state;
    private final GraphReadReason reason;
    private final Witness witness;

    private GraphDeviceWitnessOutcome(GraphProjectionState state, GraphReadReason reason, Witness witness) {
        this.state = Objects.requireNonNull(state, "GRAPH_STATE_REQUIRED");
        this.reason = Objects.requireNonNull(reason, "GRAPH_REASON_REQUIRED");
        this.witness = witness;
        if (state == GraphProjectionState.VERIFIED_BOUNDED
                ? witness == null || reason != GraphReadReason.NONE
                : witness != null || reason == GraphReadReason.NONE) {
            throw new IllegalArgumentException("GRAPH_DEVICE_WITNESS_OUTCOME_INVALID");
        }
    }

    static GraphDeviceWitnessOutcome verified(Witness witness) {
        return new GraphDeviceWitnessOutcome(GraphProjectionState.VERIFIED_BOUNDED,
                GraphReadReason.NONE, Objects.requireNonNull(witness));
    }

    static GraphDeviceWitnessOutcome from(GraphReadOutcome outcome) {
        Objects.requireNonNull(outcome, "GRAPH_READ_OUTCOME_REQUIRED");
        if (outcome.state() == GraphProjectionState.VERIFIED_BOUNDED) {
            throw new IllegalArgumentException("GRAPH_VERIFIED_EDGE_PAGE_IS_NOT_A_WITNESS");
        }
        return new GraphDeviceWitnessOutcome(outcome.state(), outcome.reason(), null);
    }

    static GraphDeviceWitnessOutcome nonVerified(GraphProjectionState state, GraphReadReason reason) {
        if (state == GraphProjectionState.VERIFIED_BOUNDED) {
            throw new IllegalArgumentException("GRAPH_VERIFIED_WITNESS_REQUIRED");
        }
        return new GraphDeviceWitnessOutcome(state, reason, null);
    }

    GraphProjectionState state() { return state; }
    GraphReadReason reason() { return reason; }
    Optional<Witness> witness() { return Optional.ofNullable(witness); }

    @Override public String toString() { return "GraphDeviceWitnessOutcome[" + state + "," + reason + ",redacted]"; }

    record Witness(GraphOccurrenceRef root, GraphOccurrenceRef prior, int pseudonymKeyVersion,
                   Instant graphAssociationAdmittedAt, Instant diagnosticReadAt) {
        Witness {
            Objects.requireNonNull(root, "GRAPH_ROOT_REQUIRED");
            Objects.requireNonNull(prior, "GRAPH_PRIOR_REQUIRED");
            GraphValidation.millisecondInstant(graphAssociationAdmittedAt);
            Objects.requireNonNull(diagnosticReadAt, "GRAPH_DIAGNOSTIC_READ_AT_REQUIRED");
            GraphValidation.version(pseudonymKeyVersion);
            GraphValidation.millisecondInstant(diagnosticReadAt);
            if (!root.scope().equals(prior.scope()) || !prior.eventTime().isBefore(root.eventTime())
                    || root.equals(prior) || diagnosticReadAt.isBefore(root.eventTime())
                    || graphAssociationAdmittedAt.isAfter(diagnosticReadAt)) {
                throw new IllegalArgumentException("GRAPH_DEVICE_WITNESS_INVALID");
            }
        }

        @Override public String toString() { return "GraphDeviceWitness[redacted]"; }
    }
}
