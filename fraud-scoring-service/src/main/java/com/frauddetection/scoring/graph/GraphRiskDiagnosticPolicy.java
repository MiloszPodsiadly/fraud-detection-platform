package com.frauddetection.scoring.graph;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure mapper for one already owner-authorized, bounded Graph read. Callers must first use
 * GraphRiskEvidenceAccess.requireOccurrence; this policy and its input description are not authority.
 */
final class GraphRiskDiagnosticPolicy {
    Diagnostic evaluate(GraphRiskEvidenceRead read) {
        if (read == null) {
            return unavailable(ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED);
        }
        return switch (read.status()) {
            case VERIFIED_POSITIVE -> Diagnostic.observed(read.witnesses().getFirst());
            case PENDING -> new Diagnostic(State.PENDING, ReasonCode.GRAPH_OWNER_EVIDENCE_PENDING,
                    Optional.empty(), Optional.empty());
            case UNAVAILABLE -> unavailable(switch (read.reason()) {
                case PROVIDER_NOT_CONFIGURED -> ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED;
                case DEADLINE_EXCEEDED -> ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED;
                case OWNER_UNAVAILABLE -> ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE;
                default -> throw new IllegalArgumentException("GRAPH_UNAVAILABLE_REASON_INVALID");
            });
            case DENIED -> new Diagnostic(State.DENIED, ReasonCode.GRAPH_AUTHORITY_DENIED,
                    Optional.empty(), Optional.empty());
            case CONFLICT -> new Diagnostic(State.CONFLICT, ReasonCode.GRAPH_OWNER_CONFLICT,
                    Optional.empty(), Optional.empty());
            case TRUNCATED_UNVERIFIED -> new Diagnostic(State.TRUNCATED_UNVERIFIED,
                    ReasonCode.GRAPH_CANDIDATE_SET_INCOMPLETE, Optional.empty(), Optional.empty());
        };
    }

    private static Diagnostic unavailable(ReasonCode reason) {
        return new Diagnostic(State.UNAVAILABLE, reason, Optional.empty(), Optional.empty());
    }

    enum State { OBSERVED, PENDING, UNAVAILABLE, DENIED, CONFLICT, TRUNCATED_UNVERIFIED }

    enum Predicate { GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED }

    enum RiskInference { NOT_CALIBRATED }

    enum OriginalScoringKnowledge { NOT_ESTABLISHED }

    enum ReasonCode {
        GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED("An earlier owner-verified occurrence shares the scoped Device pseudonym; the read is retrospective, not a scoring-time claim."),
        GRAPH_OWNER_EVIDENCE_PENDING("Authorized Graph evidence is pending."),
        GRAPH_PROVIDER_NOT_CONFIGURED("No Graph evidence provider is configured."),
        GRAPH_AUTHORITY_UNAVAILABLE("Authorized Graph evidence is currently unavailable."),
        GRAPH_READ_DEADLINE_EXCEEDED("The bounded Graph evidence read exceeded its deadline."),
        GRAPH_ACCESS_REJECTED("The bounded Graph evidence worker could not accept the read."),
        GRAPH_AUTHORITY_DENIED("The current authority did not permit this Graph evidence read."),
        GRAPH_OWNER_CONFLICT("The current owner reported conflicting occurrence evidence."),
        GRAPH_CANDIDATE_SET_INCOMPLETE("The bounded Graph candidate set was incomplete.");

        private final String explanation;

        ReasonCode(String explanation) { this.explanation = explanation; }

        String wireValue() { return name(); }
        String explanation() { return explanation; }
    }

    /** No score, risk level, absence claim, raw identifier or caller-controlled explanation is representable. */
    record Diagnostic(State state, ReasonCode reasonCode, Optional<Predicate> predicate,
                      Optional<GraphRiskEvidenceRead.WitnessDescription> witness) {
        Diagnostic {
            Objects.requireNonNull(state, "GRAPH_DIAGNOSTIC_STATE_REQUIRED");
            Objects.requireNonNull(reasonCode, "GRAPH_DIAGNOSTIC_REASON_REQUIRED");
            predicate = Objects.requireNonNull(predicate, "GRAPH_DIAGNOSTIC_PREDICATE_REQUIRED");
            witness = Objects.requireNonNull(witness, "GRAPH_DIAGNOSTIC_WITNESS_REQUIRED");
            boolean observed = state == State.OBSERVED;
            if (observed != predicate.isPresent() || observed != witness.isPresent()
                    || observed != (reasonCode == ReasonCode.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED)
                    || !reasonMatchesState(state, reasonCode)) {
                throw new IllegalArgumentException("GRAPH_DIAGNOSTIC_INCONSISTENT");
            }
        }

        private static boolean reasonMatchesState(State state, ReasonCode reason) {
            return switch (state) {
                case OBSERVED -> reason == ReasonCode.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED;
                case PENDING -> reason == ReasonCode.GRAPH_OWNER_EVIDENCE_PENDING;
                case UNAVAILABLE -> reason == ReasonCode.GRAPH_PROVIDER_NOT_CONFIGURED
                        || reason == ReasonCode.GRAPH_AUTHORITY_UNAVAILABLE
                        || reason == ReasonCode.GRAPH_READ_DEADLINE_EXCEEDED
                        || reason == ReasonCode.GRAPH_ACCESS_REJECTED;
                case DENIED -> reason == ReasonCode.GRAPH_AUTHORITY_DENIED;
                case CONFLICT -> reason == ReasonCode.GRAPH_OWNER_CONFLICT;
                case TRUNCATED_UNVERIFIED -> reason == ReasonCode.GRAPH_CANDIDATE_SET_INCOMPLETE;
            };
        }

        String explanation() { return reasonCode.explanation(); }
        RiskInference riskInference() { return RiskInference.NOT_CALIBRATED; }
        OriginalScoringKnowledge originalScoringKnowledge() { return OriginalScoringKnowledge.NOT_ESTABLISHED; }

        @Override public String toString() {
            return "GraphRiskDiagnostic[" + state + "," + reasonCode.wireValue()
                    + ",NOT_CALIBRATED,redacted]";
        }

        static Diagnostic observed(GraphRiskEvidenceRead.WitnessDescription witness) {
            return new Diagnostic(State.OBSERVED, ReasonCode.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED,
                    Optional.of(Predicate.GRAPH_SHARED_DEVICE_OCCURRENCES_OBSERVED), Optional.of(witness));
        }
    }
}
