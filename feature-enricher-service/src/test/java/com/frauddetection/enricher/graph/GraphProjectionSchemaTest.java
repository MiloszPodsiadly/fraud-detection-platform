package com.frauddetection.enricher.graph;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphProjectionSchemaTest {
    private static final Instant EVENT = Instant.parse("2026-11-18T10:00:00.000Z");

    @Test
    void bsonRoundTripPreservesNanosecondScoredOccurrenceAndLifecycle() {
        GraphStoredEdge stored = stored(scope("issuer", "epoch"), "event-one", "same-device",
                "2026-11-18T10:00:01.123456789Z");

        GraphStoredEdge decoded = GraphProjectionDocumentCodec.decode(GraphProjectionDocumentCodec.encode(stored));

        assertThat(decoded).isEqualTo(stored);
        assertThat(decoded.edge().evidence().scoredOccurrence().createdAt())
                .isEqualTo(Instant.parse("2026-11-18T10:00:01.123456789Z"));
        assertThat(GraphProjectionDocumentCodec.encode(stored).toJson())
                .doesNotContain("assertionBytes", "rawDeviceId", "merchantId", "event-one", "same-device");
    }

    @Test
    void graphDocumentDecoderRejectsUnexpectedNestedFieldsEvenAfterOutOfBandMutation() {
        Document tampered = GraphProjectionDocumentCodec.encode(stored(scope("issuer", "epoch"),
                "event-one", "same-device", "2026-11-18T10:00:01.123456789Z"));
        tampered.get("evidence", Document.class).append("unreviewed", "sensitive");

        assertThatThrownBy(() -> GraphProjectionDocumentCodec.decode(tampered))
                .isInstanceOf(IllegalStateException.class).hasMessage("GRAPH_STORE_DOCUMENT_INVALID");
    }

    @Test
    void legacyV1ProofEncodingFailsClosedWithoutBeingReinterpretedAsV2() {
        Document legacy = GraphProjectionDocumentCodec.encode(stored(scope("issuer", "epoch"),
                "event-one", "same-device", "2026-11-18T10:00:01.123456789Z"));
        legacy.put("schemaVersion", 1);
        Document version = legacy.get("projectionVersion", Document.class);
        version.put("schemaVersion", 1);
        version.put("canonicalKeyVersion", 1);
        legacy.get("evidence", Document.class).append("coverageWatermark", java.util.Date.from(EVENT));

        assertThatThrownBy(() -> GraphProjectionDocumentCodec.decode(legacy))
                .isInstanceOf(IllegalStateException.class).hasMessage("GRAPH_STORE_DOCUMENT_INVALID");
    }

    @Test
    void schemaIsStrictAtRootAndEveryNestedEvidenceAndLifecycleObject() {
        Document jsonSchema = GraphProjectionSchema.validator().get("$jsonSchema", Document.class);
        assertThat(jsonSchema.getBoolean("additionalProperties")).isFalse();
        Document properties = jsonSchema.get("properties", Document.class);
        assertThat(properties.get("evidence", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("lifecycle", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("scope", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("occurrence", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("device", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("projectionVersion", Document.class).getBoolean("additionalProperties")).isFalse();
        assertThat(properties.get("edgeType", Document.class).getList("enum", String.class))
                .containsExactly(GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name());
    }

    private static GraphStoredEdge stored(GraphIdentityScope scope, String event, String device, String scoredAt) {
        GraphOccurrenceRef occurrence = new GraphOccurrenceRef(scope, token(event), EVENT);
        GraphScoredOccurrenceRef scored = new GraphScoredOccurrenceRef(token("score-" + event), Instant.parse(scoredAt),
                token("fingerprint-" + event), GraphScoredOccurrenceRef.INITIAL_RUN);
        GraphEvidenceRef evidence = new GraphEvidenceRef(scope, occurrence, token(device), 7,
                token("receipt-" + event), token("raw-" + event), token("enriched-" + event), token(event),
                token("coverage-" + event), token("policy-" + event), scored, token("snapshot-" + event),
                EVENT.minusSeconds(300), EVENT, 1, EVENT.plus(Duration.ofDays(30)));
        GraphEdgeFact edge = new GraphEdgeFact(GraphProjectionVersion.CURRENT, GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE,
                GraphNodeRef.occurrence(occurrence), GraphNodeRef.device(scope, evidence.associatedIdentityToken(), 7), evidence);
        return new GraphStoredEdge(edge, GraphEdgeLifecycle.ACTIVE, EVENT.plusSeconds(5).truncatedTo(ChronoUnit.MILLIS),
                evidence.expiresAt());
    }

    private static GraphIdentityScope scope(String issuer, String epoch) {
        return new GraphIdentityScope(token(issuer), token("institution-a"), token("source-a"), token(epoch), 2);
    }

    private static String token(String value) { return GraphCanonical.digest("schema-test", value); }
}
