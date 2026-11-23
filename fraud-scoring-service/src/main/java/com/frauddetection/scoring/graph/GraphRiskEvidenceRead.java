package com.frauddetection.scoring.graph;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded, redacted description of the private Graph read result. */
record GraphRiskEvidenceRead(Status status, Reason reason, List<WitnessDescription> witnesses) {
    static final int MAX_WITNESSES = 1;
    private static final Duration MAX_WITNESS_WINDOW = Duration.ofDays(30);

    GraphRiskEvidenceRead {
        Objects.requireNonNull(status, "GRAPH_EVIDENCE_STATUS_REQUIRED");
        Objects.requireNonNull(reason, "GRAPH_EVIDENCE_REASON_REQUIRED");
        witnesses = List.copyOf(Objects.requireNonNull(witnesses, "GRAPH_EVIDENCE_WITNESSES_REQUIRED"));
        if (witnesses.size() > MAX_WITNESSES) {
            throw new IllegalArgumentException("GRAPH_EVIDENCE_WITNESS_LIMIT");
        }
        boolean positive = status == Status.VERIFIED_POSITIVE;
        if (positive != (witnesses.size() == 1)
                || positive != (reason == Reason.PRIOR_ASSOCIATION_OBSERVED)
                || (!positive && reason == Reason.PRIOR_ASSOCIATION_OBSERVED)
                || !reason.allowedFor(status)) {
            throw new IllegalArgumentException("GRAPH_EVIDENCE_READ_INCONSISTENT");
        }
    }

    static GraphRiskEvidenceRead verifiedPositive(WitnessDescription witness) {
        return new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE, Reason.PRIOR_ASSOCIATION_OBSERVED,
                List.of(Objects.requireNonNull(witness, "GRAPH_WITNESS_REQUIRED")));
    }

    static GraphRiskEvidenceRead pending(Reason reason) {
        return new GraphRiskEvidenceRead(Status.PENDING, reason, List.of());
    }

    static GraphRiskEvidenceRead unavailable(Reason reason) {
        return new GraphRiskEvidenceRead(Status.UNAVAILABLE, reason, List.of());
    }

    static GraphRiskEvidenceRead denied() {
        return new GraphRiskEvidenceRead(Status.DENIED, Reason.AUTHORITY_DENIED, List.of());
    }

    static GraphRiskEvidenceRead conflict() {
        return new GraphRiskEvidenceRead(Status.CONFLICT, Reason.OWNER_CONFLICT, List.of());
    }

    static GraphRiskEvidenceRead truncated() {
        return new GraphRiskEvidenceRead(Status.TRUNCATED_UNVERIFIED, Reason.CANDIDATE_SET_INCOMPLETE, List.of());
    }

    @Override public String toString() {
        return "GraphRiskEvidenceRead[" + status + "," + reason + ",witnessCount=" + witnesses.size() + ",redacted]";
    }

    enum Status {
        VERIFIED_POSITIVE,
        PENDING,
        UNAVAILABLE,
        DENIED,
        CONFLICT,
        TRUNCATED_UNVERIFIED
    }

    enum Reason {
        PRIOR_ASSOCIATION_OBSERVED,
        PROVIDER_NOT_CONFIGURED,
        OWNER_PENDING,
        OWNER_UNAVAILABLE,
        DEADLINE_EXCEEDED,
        AUTHORITY_DENIED,
        OWNER_CONFLICT,
        CANDIDATE_SET_INCOMPLETE;

        boolean allowedFor(Status status) {
            return switch (status) {
                case VERIFIED_POSITIVE -> this == PRIOR_ASSOCIATION_OBSERVED;
                case PENDING -> this == OWNER_PENDING;
                case UNAVAILABLE -> this == PROVIDER_NOT_CONFIGURED || this == OWNER_UNAVAILABLE
                        || this == DEADLINE_EXCEEDED;
                case DENIED -> this == AUTHORITY_DENIED;
                case CONFLICT -> this == OWNER_CONFLICT;
                case TRUNCATED_UNVERIFIED -> this == CANDIDATE_SET_INCOMPLETE;
            };
        }
    }

    /** Carries temporal meaning only; source and occurrence identifiers remain inside the authority adapter. */
    record WitnessDescription(Instant rootEventTime, Instant priorEventTime,
                               Instant graphAssociationAdmittedAt, Instant diagnosticReadAt) {
        WitnessDescription {
            requireMillis(rootEventTime);
            requireMillis(priorEventTime);
            requireMillis(graphAssociationAdmittedAt);
            requireMillis(diagnosticReadAt);
            Duration priorWindow = Duration.between(priorEventTime, rootEventTime);
            if (!priorEventTime.isBefore(rootEventTime) || priorWindow.compareTo(MAX_WITNESS_WINDOW) > 0
                    || diagnosticReadAt.isBefore(rootEventTime)
                    || graphAssociationAdmittedAt.isAfter(diagnosticReadAt)) {
                throw new IllegalArgumentException("GRAPH_WITNESS_TIME_ORDER_INVALID");
            }
        }

        private static void requireMillis(Instant value) {
            Objects.requireNonNull(value, "GRAPH_WITNESS_TIME_REQUIRED");
            if (value.getNano() % ChronoUnit.MILLIS.getDuration().toNanos() != 0) {
                throw new IllegalArgumentException("GRAPH_WITNESS_TIME_PRECISION_INVALID");
            }
        }

        @Override public String toString() { return "GraphRiskWitnessDescription[redacted]"; }
    }
}
