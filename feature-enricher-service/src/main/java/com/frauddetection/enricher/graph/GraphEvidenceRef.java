package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance references required to re-resolve a relation with its owners.
 * These commitments and references describe evidence; they do not authenticate it.
 */
public record GraphEvidenceRef(GraphIdentityScope scope, GraphOccurrenceRef occurrence,
                               String associatedIdentityToken, int pseudonymKeyVersion,
                               String sourceReceiptReference, String rawCommitment, String enrichedCommitment,
                               String ownerAssertionReference, String coverageReference, String policyReference,
                               GraphScoredOccurrenceRef scoredOccurrence, String stageASnapshotCommitment,
                               Instant coverageFrom, Instant asOf, long coverageGeneration, Instant expiresAt) {
    public GraphEvidenceRef {
        Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        Objects.requireNonNull(occurrence, "GRAPH_OCCURRENCE_REQUIRED");
        if (!scope.equals(occurrence.scope())) {
            throw new IllegalArgumentException("GRAPH_EVIDENCE_SCOPE_MISMATCH");
        }
        GraphValidation.token(associatedIdentityToken);
        GraphValidation.version(pseudonymKeyVersion);
        GraphValidation.token(sourceReceiptReference);
        GraphValidation.token(rawCommitment);
        GraphValidation.token(enrichedCommitment);
        GraphValidation.token(ownerAssertionReference);
        GraphValidation.token(coverageReference);
        GraphValidation.token(policyReference);
        Objects.requireNonNull(scoredOccurrence, "GRAPH_INITIAL_SCORE_REQUIRED");
        GraphValidation.token(stageASnapshotCommitment);
        GraphValidation.millisecondInstant(coverageFrom);
        GraphValidation.millisecondInstant(asOf);
        GraphValidation.millisecondInstant(expiresAt);
        if (coverageGeneration < 1 || coverageFrom.isAfter(asOf)
                || !occurrence.eventTime().equals(asOf)
                || !expiresAt.isAfter(asOf)) {
            throw new IllegalArgumentException("GRAPH_EVIDENCE_TEMPORAL_SCOPE_INVALID");
        }
    }

    public String canonicalReferenceId() {
        return GraphCanonical.digest("evidence-ref/v2",
                scope.sourceIssuerToken(), scope.institutionToken(), scope.sourceToken(), scope.sourceEpochToken(),
                Integer.toString(scope.identityVersion()), occurrence.occurrenceToken(),
                Long.toString(occurrence.eventTime().toEpochMilli()), associatedIdentityToken,
                Integer.toString(pseudonymKeyVersion), sourceReceiptReference, rawCommitment, enrichedCommitment,
                ownerAssertionReference, coverageReference, policyReference, scoredOccurrence.scoredEventToken(),
                scoredOccurrence.createdAt().toString(), scoredOccurrence.fingerprint(),
                scoredOccurrence.runId(), stageASnapshotCommitment, Long.toString(coverageFrom.toEpochMilli()),
                Long.toString(asOf.toEpochMilli()), Long.toString(coverageGeneration),
                Long.toString(expiresAt.toEpochMilli()));
    }

    @Override public String toString() { return "GraphEvidenceRef[redacted]"; }
}
