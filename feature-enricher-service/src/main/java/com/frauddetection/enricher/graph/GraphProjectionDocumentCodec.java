package com.frauddetection.enricher.graph;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Explicit strict BSON mapping. Exact scored timestamps are strings because BSON dates lose nanos. */
final class GraphProjectionDocumentCodec {
    private GraphProjectionDocumentCodec() { }

    static Document encode(GraphStoredEdge stored) {
        GraphEdgeFact edge = stored.edge();
        GraphEvidenceRef evidence = edge.evidence();
        GraphIdentityScope scope = evidence.scope();
        GraphScoredOccurrenceRef score = evidence.scoredOccurrence();
        Document scopeDoc = new Document("sourceIssuerToken", scope.sourceIssuerToken())
                .append("institutionToken", scope.institutionToken()).append("sourceToken", scope.sourceToken())
                .append("sourceEpochToken", scope.sourceEpochToken()).append("identityVersion", scope.identityVersion());
        Document projection = new Document("schemaVersion", edge.projectionVersion().schemaVersion())
                .append("canonicalKeyVersion", edge.projectionVersion().canonicalKeyVersion());
        Document occurrence = new Document("token", evidence.occurrence().occurrenceToken())
                .append("eventTime", date(evidence.occurrence().eventTime()));
        Document device = new Document("token", evidence.associatedIdentityToken())
                .append("keyVersion", evidence.pseudonymKeyVersion());
        Document scored = new Document("eventToken", score.scoredEventToken()).append("createdAt", score.createdAt().toString())
                .append("fingerprint", score.fingerprint()).append("runId", score.runId());
        Document proof = new Document("referenceId", evidence.canonicalReferenceId())
                .append("sourceReceiptReference", evidence.sourceReceiptReference())
                .append("rawCommitment", evidence.rawCommitment()).append("enrichedCommitment", evidence.enrichedCommitment())
                .append("ownerAssertionReference", evidence.ownerAssertionReference())
                .append("coverageReference", evidence.coverageReference()).append("policyReference", evidence.policyReference())
                .append("score", scored).append("stageASnapshotCommitment", evidence.stageASnapshotCommitment())
                .append("coverageFrom", date(evidence.coverageFrom())).append("asOf", date(evidence.asOf()))
                .append("coverageGeneration", evidence.coverageGeneration())
                .append("coverageWatermark", date(evidence.coverageWatermark())).append("expiresAt", date(evidence.expiresAt()));
        Document lifecycle = new Document("state", stored.lifecycle().name())
                .append("admittedAt", date(stored.admittedAt())).append("validUntil", date(stored.validUntil()));
        return new Document("_id", edge.canonicalId()).append("schemaVersion", GraphProjectionSchema.SCHEMA_VERSION)
                .append("projectionVersion", projection).append("scope", scopeDoc).append("edgeType", edge.type().name())
                .append("occurrence", occurrence).append("device", device).append("evidence", proof).append("lifecycle", lifecycle);
    }

    static GraphStoredEdge decode(Document document) {
        try {
            requireKeys(document, Set.of("_id", "schemaVersion", "projectionVersion", "scope", "edgeType",
                    "occurrence", "device", "evidence", "lifecycle"));
            Document scopeDoc = child(document, "scope");
            requireKeys(scopeDoc, Set.of("sourceIssuerToken", "institutionToken", "sourceToken", "sourceEpochToken", "identityVersion"));
            GraphIdentityScope scope = new GraphIdentityScope(scopeDoc.getString("sourceIssuerToken"),
                    scopeDoc.getString("institutionToken"), scopeDoc.getString("sourceToken"),
                    scopeDoc.getString("sourceEpochToken"), scopeDoc.getInteger("identityVersion"));
            Document projection = child(document, "projectionVersion");
            requireKeys(projection, Set.of("schemaVersion", "canonicalKeyVersion"));
            GraphProjectionVersion projectionVersion = new GraphProjectionVersion(projection.getInteger("schemaVersion"),
                    projection.getInteger("canonicalKeyVersion"));
            Document occurrenceDoc = child(document, "occurrence");
            requireKeys(occurrenceDoc, Set.of("token", "eventTime"));
            GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, occurrenceDoc.getString("token"), instant(occurrenceDoc, "eventTime"));
            Document device = child(document, "device");
            requireKeys(device, Set.of("token", "keyVersion"));
            Document proof = child(document, "evidence");
            requireKeys(proof, Set.of("referenceId", "sourceReceiptReference", "rawCommitment", "enrichedCommitment",
                    "ownerAssertionReference", "coverageReference", "policyReference", "score", "stageASnapshotCommitment",
                    "coverageFrom", "asOf", "coverageGeneration", "coverageWatermark", "expiresAt"));
            Document scoreDoc = child(proof, "score");
            requireKeys(scoreDoc, Set.of("eventToken", "createdAt", "fingerprint", "runId"));
            GraphScoredOccurrenceRef score = new GraphScoredOccurrenceRef(scoreDoc.getString("eventToken"),
                    Instant.parse(scoreDoc.getString("createdAt")), scoreDoc.getString("fingerprint"), scoreDoc.getString("runId"));
            GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrence, device.getString("token"),
                    device.getInteger("keyVersion"), proof.getString("sourceReceiptReference"), proof.getString("rawCommitment"),
                    proof.getString("enrichedCommitment"), proof.getString("ownerAssertionReference"), proof.getString("coverageReference"),
                    proof.getString("policyReference"), score, proof.getString("stageASnapshotCommitment"),
                    instant(proof, "coverageFrom"), instant(proof, "asOf"), proof.getLong("coverageGeneration"),
                    instant(proof, "coverageWatermark"), instant(proof, "expiresAt"));
            if (!evidence.canonicalReferenceId().equals(proof.getString("referenceId"))) { throw invalidDocument(); }
            GraphEdgeFact edge = new GraphEdgeFact(projectionVersion, GraphEdgeType.valueOf(document.getString("edgeType")),
                    GraphNodeRef.occurrence(occurrence), GraphNodeRef.device(scope, device.getString("token"), device.getInteger("keyVersion")), evidence);
            if (!edge.canonicalId().equals(document.getString("_id"))) { throw invalidDocument(); }
            Document lifecycle = child(document, "lifecycle");
            requireKeys(lifecycle, Set.of("state", "admittedAt", "validUntil"));
            return new GraphStoredEdge(edge, GraphEdgeLifecycle.valueOf(lifecycle.getString("state")),
                    instant(lifecycle, "admittedAt"), instant(lifecycle, "validUntil"));
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException state && "GRAPH_STORE_DOCUMENT_INVALID".equals(state.getMessage())) { throw state; }
            throw invalidDocument();
        }
    }

    private static Document child(Document parent, String field) {
        Document result = parent.get(field, Document.class);
        if (result == null) { throw invalidDocument(); }
        return result;
    }

    private static void requireKeys(Document document, Set<String> expected) {
        if (!document.keySet().equals(expected)) { throw invalidDocument(); }
    }

    private static Date date(Instant instant) {
        GraphValidation.millisecondInstant(instant);
        return Date.from(instant);
    }

    private static Instant instant(Document parent, String field) {
        Date value = parent.getDate(field);
        if (value == null) { throw invalidDocument(); }
        return value.toInstant();
    }

    private static IllegalStateException invalidDocument() {
        return new IllegalStateException("GRAPH_STORE_DOCUMENT_INVALID");
    }
}
