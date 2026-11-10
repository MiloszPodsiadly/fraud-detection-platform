package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

final class SourceOccurrenceSchema {
    static final String OCCURRENCES = "source_scored_occurrences";
    static final String COUNTS = "source_scored_occurrence_counts";
    static final List<String> COLLECTIONS = List.of(OCCURRENCES, COUNTS);
    static final int MAX_RUNS = 32;
    static final String SOURCE_RUN_INDEX = "scored_source_runs";
    static void initialize(MongoDatabase db, boolean owner) {
        long present = db.listCollectionNames().into(new ArrayList<>()).stream().filter(COLLECTIONS::contains).count();
        if (present != COLLECTIONS.size() && (present != 0 || !owner)) { throw invalid(); }
        for (String name : COLLECTIONS) {
            var expectedIndexes = name.equals(COUNTS) ? List.of(new Document("_id", 1)) : List.of(new Document("_id", 1),
                    new Document("eventId", 1).append("eventCreatedAt", 1), new Document("state", 1).append("expiresAt", 1),
                    new Document("receiptId", 1));
            var expectedNames = name.equals(COUNTS) ? List.of("_id_")
                    : List.of("_id_", "scored_identity_unique", "scored_recovery", SOURCE_RUN_INDEX);
            Document existing = db.listCollections().filter(eq("name", name)).first();
            if (existing == null) {
                db.createCollection(name, new CreateCollectionOptions().validationOptions(new ValidationOptions().validator(validator(name))
                        .validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
                if (!name.equals(COUNTS)) {
                    db.getCollection(name).createIndex(expectedIndexes.get(1), new IndexOptions().name("scored_identity_unique").unique(true));
                    db.getCollection(name).createIndex(expectedIndexes.get(2), new IndexOptions().name("scored_recovery"));
                    db.getCollection(name).createIndex(expectedIndexes.get(3), new IndexOptions().name(SOURCE_RUN_INDEX));
                }
                existing = db.listCollections().filter(eq("name", name)).first();
            }
            Document options = existing.get("options", Document.class);
            if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                    || !validator(name).equals(options.get("validator")) || !"strict".equals(options.get("validationLevel"))
                    || !"error".equals(options.get("validationAction"))) { throw invalid(); }
            var indexes = db.getCollection(name).listIndexes().into(new ArrayList<>());
            if (indexes.size() != expectedIndexes.size()) { throw invalid(); }
            for (Document index : indexes) {
                int expected = expectedIndexes.indexOf(index.get("key"));
                if (expected < 0 || !expectedNames.get(expected).equals(index.getString("name")) || index.containsKey("expireAfterSeconds")
                        || index.containsKey("partialFilterExpression") || index.containsKey("collation")
                        || Boolean.TRUE.equals(index.get("sparse")) || Boolean.TRUE.equals(index.get("hidden"))
                        || expected > 1 && Boolean.TRUE.equals(index.get("unique"))) { throw invalid(); }
                if (new Document("eventId", 1).append("eventCreatedAt", 1).equals(index.get("key"))
                        && !Boolean.TRUE.equals(index.get("unique"))) { throw invalid(); }
            }
        }
    }
    private static Document validator(String name) {
        Document fields = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", name.equals(COUNTS) ? List.of(1) : List.of(1, 2)));
        if (name.equals(COUNTS)) { fields.append("count", type("int").append("minimum", 1).append("maximum", MAX_RUNS)); }
        else {
            fields.append("receiptId", hash()).append("enrichedCommitment", hash()).append("runId", reference())
                    .append("eventId", reference()).append("eventCreatedAt", type("string").append("maxLength", 64))
                    .append("fingerprint", hash()).append("createdAt", type("date")).append("expiresAt", type("date"))
                    .append("state", type("string").append("enum", List.of("PENDING", "PUBLISHING", "PUBLISHED", "RETRY_EXHAUSTED", "CONFLICT", "EXPIRED")))
                    .append("attempts", type("int").append("minimum", 0).append("maximum", 8))
                    .append("recoveryEpoch", type("int").append("minimum", 0).append("maximum", 3))
                    .append("lastAttemptCode", type("string").append("pattern", "^[A-Z0-9_]{1,64}$"))
                    .append("nextAttemptAt", type("date")).append("leaseId", reference()).append("leaseUntil", type("date"))
                    .append("lastRedriveAt", type("date"))
                    .append("redriveReason", type("string").append("enum", List.of("BROKER_RECOVERED", "OPERATOR_RECONCILIATION")))
                    .append("keyId", reference()).append("nonce", type("binData")).append("ciphertext", type("binData"));
        }
        var required = new ArrayList<>(fields.keySet());
        required.removeAll(List.of("keyId", "nonce", "ciphertext", "recoveryEpoch", "lastAttemptCode", "nextAttemptAt",
                "leaseId", "leaseUntil", "lastRedriveAt", "redriveReason"));
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", required).append("properties", fields));
    }
    static void migrateRecoveryValidator(MongoDatabase db, boolean owner) {
        if (!owner) { throw new IllegalStateException("SOURCE_OCCURRENCE_MIGRATION_OWNER_REQUIRED"); }
        Document existing = db.listCollections().filter(eq("name", OCCURRENCES)).first();
        if (existing == null) { throw invalid(); }
        Document options = existing.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validatorV1().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) { throw invalid(); }
        Document result = db.runCommand(new Document("collMod", OCCURRENCES).append("validator", validator(OCCURRENCES))
                .append("validationLevel", "strict").append("validationAction", "error"));
        if (result.get("ok", Number.class) == null || result.get("ok", Number.class).intValue() != 1) { throw invalid(); }
    }
    private static Document validatorV1() {
        Document fields = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("receiptId", hash()).append("enrichedCommitment", hash()).append("runId", reference())
                .append("eventId", reference()).append("eventCreatedAt", type("string").append("maxLength", 64))
                .append("fingerprint", hash()).append("createdAt", type("date")).append("expiresAt", type("date"))
                .append("state", type("string").append("enum", List.of("PENDING", "PUBLISHED", "CONFLICT", "EXPIRED")))
                .append("attempts", type("int").append("minimum", 0).append("maximum", 8))
                .append("keyId", reference()).append("nonce", type("binData")).append("ciphertext", type("binData"));
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", new ArrayList<>(fields.keySet()).stream().filter(k -> !List.of("keyId", "nonce", "ciphertext").contains(k)).toList())
                .append("properties", fields));
    }
    private static Document reference() { return type("string").append("pattern", "^[A-Za-z0-9._:-]{1,128}$"); }
    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document type(String value) { return new Document("bsonType", value); }
    private static IllegalStateException invalid() { return new IllegalStateException("SOURCE_OCCURRENCE_SCHEMA_INCOMPATIBLE"); }
}
