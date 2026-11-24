package com.frauddetection.scoring.graph;

import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceReceiptIdentity;
import com.frauddetection.common.events.source.SourceScope;
import com.frauddetection.scoring.context.ScoringContext;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded, redacted description of the private Graph read result. */
record GraphRiskEvidenceRead(Status status, Reason reason, List<WitnessDescription> witnesses, Binding binding) {
    static final int MAX_WITNESSES = 1;
    private static final Duration MAX_WITNESS_WINDOW = Duration.ofDays(30);

    GraphRiskEvidenceRead(Status status, Reason reason, List<WitnessDescription> witnesses) {
        this(status, reason, witnesses, null);
    }

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
        if (positive && binding != null && !witnesses.getFirst().hasStableIdentity()) {
            throw new IllegalArgumentException("GRAPH_WITNESS_COMMITMENT_REQUIRED");
        }
    }

    static GraphRiskEvidenceRead verifiedPositive(WitnessDescription witness) {
        return new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE, Reason.PRIOR_ASSOCIATION_OBSERVED,
                List.of(Objects.requireNonNull(witness, "GRAPH_WITNESS_REQUIRED")));
    }

    static GraphRiskEvidenceRead verifiedPositive(WitnessDescription witness, ScoringContext context,
                                                   String exactOccurrenceIdentity) {
        return new GraphRiskEvidenceRead(Status.VERIFIED_POSITIVE, Reason.PRIOR_ASSOCIATION_OBSERVED,
                List.of(Objects.requireNonNull(witness, "GRAPH_WITNESS_REQUIRED")),
                Binding.forContext(context, exactOccurrenceIdentity));
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

    boolean isBoundTo(ScoringContext context) {
        return binding != null && binding.matches(context);
    }

    /** Compares independently authorized reads of one bound witness while allowing its read clock to advance. */
    boolean sameBoundWitnessAtNonRegressingReadTime(GraphRiskEvidenceRead fresh) {
        if (status != Status.VERIFIED_POSITIVE || fresh == null || fresh.status != Status.VERIFIED_POSITIVE
                || binding == null || !binding.equals(fresh.binding)
                || witnesses.size() != 1 || fresh.witnesses.size() != 1) {
            return false;
        }
        WitnessDescription previous = witnesses.getFirst();
        WitnessDescription current = fresh.witnesses.getFirst();
        return previous.hasStableIdentity() && current.hasStableIdentity()
                && previous.rootEventTime().equals(current.rootEventTime())
                && previous.priorEventTime().equals(current.priorEventTime())
                && previous.graphAssociationAdmittedAt().equals(current.graphAssociationAdmittedAt())
                && previous.stableWitnessCommitment().equals(current.stableWitnessCommitment())
                && !current.diagnosticReadAt().isBefore(previous.diagnosticReadAt());
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

    /** Opaque commitments only; neither commitment is an owner attestation. */
    record Binding(String contextCommitment, String occurrenceCommitment) {
        Binding {
            if (!isDigest(contextCommitment) || !isDigest(occurrenceCommitment)) {
                throw new IllegalArgumentException("GRAPH_EVIDENCE_BINDING_INVALID");
            }
        }

        static Binding forContext(ScoringContext context, String exactOccurrenceIdentity) {
            Objects.requireNonNull(context, "GRAPH_SCORING_CONTEXT_REQUIRED");
            List<String> occurrence = occurrenceFields(exactOccurrenceIdentity);
            String contextCommitment;
            try {
                contextCommitment = contextCommitment(context);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("GRAPH_EVIDENCE_BINDING_SOURCE_INVALID");
            }
            if (!isDigest(contextCommitment)) {
                throw new IllegalArgumentException("GRAPH_EVIDENCE_BINDING_SOURCE_INVALID");
            }
            return new Binding(contextCommitment, digest(occurrence));
        }

        /**
         * Compares the enriched event, verified Source scope/receipt, scoring mode, correlation and receive time.
         * The normalized features are covered by the enriched commitment and SourceLineage input match. This
         * comparison cannot authorize the occurrence; the access adapter must freshly verify that exact owner row.
         */
        boolean matches(ScoringContext context) {
            try {
                return context != null && contextCommitment.equals(contextCommitment(context));
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        private static String contextCommitment(ScoringContext context) {
            SourceLineage lineage = context.sourceLineage();
            SourceScope scope = lineage.source();
            if (lineage.state() != SourceLineage.State.VERIFIED || scope == null
                    || !isDigest(lineage.receiptId()) || !isDigest(lineage.eventCommitment())) {
                return "";
            }
            try {
                String enriched = SourceEventCommitment.enriched(context.transaction());
                if (!enriched.equals(lineage.eventCommitment())
                        || !scope.eventTime().equals(context.transaction().transactionTimestamp())
                        || !SourceReceiptIdentity.identity(scope).equals(lineage.receiptId())) {
                    return "";
                }
                return digest(List.of("graph-scorer-context/v1", enriched, lineage.state().name(),
                    scope.issuer(), scope.institution(), scope.source(), scope.sourceEpoch(),
                    scope.signingKeyVersion(), scope.sourceEventId(), scope.eventTime().toString(), scope.purpose(),
                    scope.audience(), scope.payloadCommitment(), lineage.receiptId(), lineage.eventCommitment(),
                    context.mode().name(), context.correlationId(), context.receivedAt().toString()));
            } catch (RuntimeException invalid) {
                return "";
            }
        }

        private static List<String> occurrenceFields(String identity) {
            if (identity == null || identity.length() > 512) {
                throw new IllegalArgumentException("GRAPH_EXACT_OCCURRENCE_INVALID");
            }
            String[] fields = identity.split("\\u001f", -1);
            if (fields.length != 7 || fields[0].isEmpty() || fields[1].isEmpty() || fields[2].isEmpty()
                    || fields[3].isEmpty() || fields[5].isEmpty()
                    || !fields[0].matches("[0-9a-f]{64}") || !fields[1].matches("[0-9a-f]{64}")
                    || !fields[2].matches("[0-9a-f]{64}") || !fields[3].matches("[0-9a-f]{64}")
                    || !fields[5].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("GRAPH_EXACT_OCCURRENCE_INVALID");
            }
            try {
                int version = Integer.parseInt(fields[4]);
                Instant eventTime = Instant.parse(fields[6]);
                if (version < 1 || version > 65_535 || eventTime.getNano() % 1_000_000 != 0
                        || !eventTime.toString().equals(fields[6])) {
                    throw new IllegalArgumentException("GRAPH_EXACT_OCCURRENCE_INVALID");
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("GRAPH_EXACT_OCCURRENCE_INVALID");
            }
            return List.of("graph-exact-occurrence/v1", fields[0], fields[1], fields[2], fields[3], fields[4],
                    fields[5], fields[6]);
        }

        private static boolean isDigest(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }

        private static String digest(List<String> fields) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    for (String field : fields) {
                        byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                        output.writeInt(encoded.length);
                        output.write(encoded);
                    }
                }
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("GRAPH_EVIDENCE_BINDING_UNAVAILABLE");
            } catch (IOException impossible) {
                throw new IllegalStateException("GRAPH_EVIDENCE_BINDING_UNAVAILABLE");
            }
        }

        @Override public String toString() { return "GraphEvidenceBinding[redacted]"; }
    }

    /** Carries temporal meaning only; source and occurrence identifiers remain inside the authority adapter. */
    record WitnessDescription(Instant rootEventTime, Instant priorEventTime,
                               Instant graphAssociationAdmittedAt, Instant diagnosticReadAt,
                               String stableWitnessCommitment) {
        WitnessDescription(Instant rootEventTime, Instant priorEventTime,
                           Instant graphAssociationAdmittedAt, Instant diagnosticReadAt) {
            this(rootEventTime, priorEventTime, graphAssociationAdmittedAt, diagnosticReadAt, null);
        }

        WitnessDescription {
            requireMillis(rootEventTime);
            requireMillis(priorEventTime);
            requireMillis(graphAssociationAdmittedAt);
            requireMillis(diagnosticReadAt);
            if (stableWitnessCommitment != null && !isDigest(stableWitnessCommitment)) {
                throw new IllegalArgumentException("GRAPH_WITNESS_COMMITMENT_INVALID");
            }
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

        private boolean hasStableIdentity() {
            return stableWitnessCommitment != null && isDigest(stableWitnessCommitment);
        }

        private static boolean isDigest(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }

        @Override public String toString() { return "GraphRiskWitnessDescription[redacted]"; }
    }
}
