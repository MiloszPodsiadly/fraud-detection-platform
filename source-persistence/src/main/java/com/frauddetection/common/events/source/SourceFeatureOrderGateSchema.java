package com.frauddetection.common.events.source;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.ne;

/** Strict, bounded Mongo gate that serializes admitted attested history per source namespace/customer. */
final class SourceFeatureOrderGateSchema {
    static final String COLLECTION = "source_feature_order_gates";
    static final int MAX_GATE_ROWS = 100_000;
    private static final String RAW = "source_lineage_raw";
    private static final String PUBLICATIONS = SourceEnrichedPublicationCipher.COLLECTION;
    private static final String RAW_PUBLICATIONS = "source_raw_publications";
    private static final String HISTORY_APPLIED = "HISTORY_APPLIED";
    private static final String LATE_REJECTED = "LATE_REJECTED";

    private SourceFeatureOrderGateSchema() { }

    static void initialize(MongoDatabase database, boolean owner, Clock clock) {
        if (database == null || clock == null) { throw invalid("MIGRATION_INPUT_REQUIRED"); }
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        if (existing == null) {
            if (!owner) { throw invalid("OWNER_MIGRATION_REQUIRED"); }
            verifyNoUnresolvedPreGateWork(database, Date.from(clock.instant()));
            database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                    .validator(validator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
        }
        verifyCollection(database);
    }

    static boolean isActive(Document row, String receiptId, String rawCommitment) {
        return row != null && row.keySet().equals(Set.of("_id", "schemaVersion", "status", "receiptId",
                "rawCommitment", "activatedAt", "resolvedAt", "resolution"))
                && row.getInteger("schemaVersion", 0) == 1 && "ACTIVE".equals(row.getString("status"))
                && receiptId.equals(row.getString("receiptId")) && rawCommitment.equals(row.getString("rawCommitment"))
                && row.get("activatedAt") instanceof Date && row.containsKey("resolvedAt") && row.get("resolvedAt") == null
                && row.containsKey("resolution") && row.get("resolution") == null;
    }

    static boolean isResolvedFor(Document row, String receiptId, String rawCommitment) {
        if (row == null || !row.keySet().equals(Set.of("_id", "schemaVersion", "status", "receiptId",
                "rawCommitment", "activatedAt", "resolvedAt", "resolution"))
                || row.getInteger("schemaVersion", 0) != 1
                || !receiptId.equals(row.getString("receiptId")) || !rawCommitment.equals(row.getString("rawCommitment"))
                || !(row.get("activatedAt") instanceof Date) || !(row.get("resolvedAt") instanceof Date)) { return false; }
        return "RESOLVED".equals(row.getString("status")) && HISTORY_APPLIED.equals(row.getString("resolution"))
                || "LATE_REJECTED".equals(row.getString("status")) && LATE_REJECTED.equals(row.getString("resolution"));
    }

    static String publicationId(String rawCommitment) {
        return SourceReceiptIdentity.digest("source-enriched-publication-id/v1", rawCommitment);
    }

    private static void verifyNoUnresolvedPreGateWork(MongoDatabase database, Date now) {
        if (database.listCollections().filter(eq("name", PUBLICATIONS)).first() == null
                || database.listCollections().filter(eq("name", RAW)).first() == null) {
            throw invalid("PRE_GATE_STATE_UNAVAILABLE");
        }
        MongoCollection<Document> publications = database.getCollection(PUBLICATIONS);
        if (publications.countDocuments(and(gt("expiresAt", now), ne("historyState", "APPLIED"))) != 0) {
            throw invalid("ENRICHED_HISTORY_PENDING");
        }
        if (database.listCollections().filter(eq("name", RAW_PUBLICATIONS)).first() != null
                && database.getCollection(RAW_PUBLICATIONS).countDocuments(and(gt("expiresAt", now),
                in("state", "PENDING", "QUARANTINED"))) != 0) {
            throw invalid("RAW_PUBLICATION_PENDING");
        }
        MongoCursor<Document> rawRows = database.getCollection(RAW).find(gt("expiresAt", now))
                .limit(MAX_GATE_ROWS + 1).iterator();
        try (rawRows) {
            int checked = 0;
            while (rawRows.hasNext()) {
                Document raw = rawRows.next();
                if (++checked > MAX_GATE_ROWS || !"BOUND".equals(raw.getString("state"))) {
                    throw invalid("RAW_LINEAGE_UNRESOLVED");
                }
                String rawCommitment = raw.getString("eventCommitment");
                Document publication = publications.find(eq("_id", publicationId(rawCommitment))).first();
                if (publication == null || !raw.getString("receiptId").equals(publication.getString("receiptId"))
                        || !rawCommitment.equals(publication.getString("rawCommitment"))
                        || !"APPLIED".equals(publication.getString("historyState"))) {
                    throw invalid("RAW_HISTORY_UNRESOLVED");
                }
            }
        }
    }

    private static void verifyCollection(MongoDatabase database) {
        Document collection = database.listCollections().filter(eq("name", COLLECTION)).first();
        Document options = collection == null ? null : collection.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validator().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) { throw invalid("SCHEMA_INVALID"); }
        List<Document> indexes = database.getCollection(COLLECTION).listIndexes().into(new java.util.ArrayList<>());
        if (indexes.size() != 1 || !"_id_".equals(indexes.getFirst().getString("name"))
                || !new Document("_id", 1).equals(indexes.getFirst().get("key"))
                || indexes.getFirst().containsKey("expireAfterSeconds") || indexes.getFirst().containsKey("partialFilterExpression")
                || indexes.getFirst().containsKey("collation") || Boolean.TRUE.equals(indexes.getFirst().get("sparse"))
                || Boolean.TRUE.equals(indexes.getFirst().get("hidden"))) { throw invalid("INDEX_INVALID"); }
    }

    private static Document validator() {
        Document common = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("receiptId", hash()).append("rawCommitment", hash()).append("activatedAt", type("date"));
        Document active = new Document(common).append("status", type("string").append("enum", List.of("ACTIVE")))
                .append("resolvedAt", type("null")).append("resolution", type("null"));
        Document resolved = new Document(common).append("status", type("string").append("enum", List.of("RESOLVED")))
                .append("resolvedAt", type("date")).append("resolution", type("string").append("enum", List.of(HISTORY_APPLIED)));
        Document late = new Document(common).append("status", type("string").append("enum", List.of("LATE_REJECTED")))
                .append("resolvedAt", type("date")).append("resolution", type("string").append("enum", List.of(LATE_REJECTED)));
        List<String> required = List.of("_id", "schemaVersion", "status", "receiptId", "rawCommitment",
                "activatedAt", "resolvedAt", "resolution");
        return new Document("$jsonSchema", new Document("oneOf", List.of(schema(active, required), schema(resolved, required),
                schema(late, required))));
    }

    private static Document schema(Document properties, List<String> required) {
        return new Document("bsonType", "object").append("additionalProperties", false)
                .append("required", required).append("properties", properties);
    }
    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document type(Object value) { return new Document("bsonType", value); }
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("SOURCE_FEATURE_ORDER_GATE_" + reason);
    }
}
