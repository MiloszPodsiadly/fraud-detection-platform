package com.frauddetection.ingest.source;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

/** Strict, bounded single-intent-per-receipt schema. */
final class MongoSourceRawPublicationSchema {
    static final String COLLECTION = "source_raw_publications";
    static final String RECOVERY_INDEX = "raw_publication_recovery";
    static final int MAX_RECOVERY_EPOCHS = 3;
    static final Document RECOVERY_KEY = new Document("state", 1).append("nextAttemptAt", 1)
            .append("leaseUntil", 1).append("expiresAt", 1);

    static void initialize(MongoDatabase database) {
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        if (existing == null) {
            database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                    .validator(validator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
            database.getCollection(COLLECTION).createIndex(RECOVERY_KEY, new IndexOptions().name(RECOVERY_INDEX));
            existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        }
        Document options = existing == null ? null : existing.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validator().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) { throw incompatible(); }
        Set<String> indexes = new HashSet<>();
        try (var iterator = database.getCollection(COLLECTION).listIndexes().iterator()) {
            while (iterator.hasNext()) {
                Document index = iterator.next();
                String name = index.getString("name");
                Document key = index.get("key", Document.class);
                if (name == null || !indexes.add(name) || index.containsKey("expireAfterSeconds")
                        || index.containsKey("partialFilterExpression") || index.containsKey("collation")
                        || Boolean.TRUE.equals(index.get("sparse")) || Boolean.TRUE.equals(index.get("hidden"))
                        || !("_id_".equals(name) && new Document("_id", 1).equals(key)
                        || RECOVERY_INDEX.equals(name) && RECOVERY_KEY.equals(key) && !Boolean.TRUE.equals(index.get("unique")))) {
                    throw incompatible();
                }
            }
        }
        if (!indexes.equals(Set.of("_id_", RECOVERY_INDEX))) { throw incompatible(); }
    }

    /** Owner-run validator-only migration. The existing bounded recovery index is unchanged. */
    static void migrateRecoveryValidator(MongoDatabase database, boolean owner) {
        if (!owner) { throw new IllegalStateException("SOURCE_RAW_PUBLICATION_MIGRATION_OWNER_REQUIRED"); }
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        Document options = existing == null ? null : existing.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validatorV1().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction")) || !hasRecoveryIndexes(database)) { throw incompatible(); }
        Document result = database.runCommand(new Document("collMod", COLLECTION).append("validator", validator())
                .append("validationLevel", "strict").append("validationAction", "error"));
        Number ok = result.get("ok", Number.class);
        if (ok == null || ok.intValue() != 1) { throw incompatible(); }
        Document migrated = database.listCollections().filter(eq("name", COLLECTION)).first();
        Document migratedOptions = migrated == null ? null : migrated.get("options", Document.class);
        if (migratedOptions == null || !validator().equals(migratedOptions.get("validator"))
                || !"strict".equals(migratedOptions.getString("validationLevel"))
                || !"error".equals(migratedOptions.getString("validationAction")) || !hasRecoveryIndexes(database)) {
            throw incompatible();
        }
    }

    private static boolean hasRecoveryIndexes(MongoDatabase database) {
        var indexes = database.getCollection(COLLECTION).listIndexes().into(new java.util.ArrayList<>());
        if (indexes.size() != 2) { return false; }
        boolean id = false;
        boolean recovery = false;
        for (Document index : indexes) {
            if (index.containsKey("expireAfterSeconds") || index.containsKey("partialFilterExpression")
                    || index.containsKey("collation") || Boolean.TRUE.equals(index.get("sparse"))
                    || Boolean.TRUE.equals(index.get("hidden"))) { return false; }
            if ("_id_".equals(index.getString("name")) && new Document("_id", 1).equals(index.get("key"))) { id = true; }
            if (RECOVERY_INDEX.equals(index.getString("name")) && RECOVERY_KEY.equals(index.get("key"))
                    && !Boolean.TRUE.equals(index.get("unique"))) { recovery = true; }
        }
        return id && recovery;
    }

    private static Document validator() {
        Document properties = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("receiptId", hash()).append("rawCommitment", hash()).append("eventId", reference())
                .append("eventCreatedAt", type("string").append("maxLength", 64)).append("createdAt", type("date"))
                .append("expiresAt", type("date")).append("state", type("string").append("enum", List.of("PENDING", "PUBLISHED", "QUARANTINED")))
                .append("attempts", type("int").append("minimum", 0).append("maximum", 8))
                .append("nextAttemptAt", type("date")).append("keyId", reference())
                .append("nonce", type("binData")).append("ciphertext", type("binData"))
                .append("leaseToken", reference()).append("leaseUntil", type("date"))
                .append("publishedAt", type("date"))
                .append("failureCode", type("string").append("enum", List.of("PUBLISH_FAILED", "AUTHORITY_DENIED", "PAYLOAD_UNAVAILABLE")))
                .append("recoveryEpoch", type("int").append("minimum", 0).append("maximum", MAX_RECOVERY_EPOCHS))
                .append("lastRedriveAt", type("date"))
                .append("redriveReason", type("string").append("enum", List.of("BROKER_RECOVERED", "OPERATOR_RECONCILIATION")));
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", List.of("_id", "schemaVersion", "receiptId", "rawCommitment", "eventId", "eventCreatedAt",
                        "createdAt", "expiresAt", "state", "attempts", "nextAttemptAt", "keyId", "nonce", "ciphertext"))
                .append("properties", properties));
    }

    static Document validatorV1() {
        Document properties = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("receiptId", hash()).append("rawCommitment", hash()).append("eventId", reference())
                .append("eventCreatedAt", type("string").append("maxLength", 64)).append("createdAt", type("date"))
                .append("expiresAt", type("date")).append("state", type("string").append("enum", List.of("PENDING", "PUBLISHED", "QUARANTINED")))
                .append("attempts", type("int").append("minimum", 0).append("maximum", 8))
                .append("nextAttemptAt", type("date")).append("keyId", reference())
                .append("nonce", type("binData")).append("ciphertext", type("binData"))
                .append("leaseToken", reference()).append("leaseUntil", type("date"))
                .append("publishedAt", type("date"))
                .append("failureCode", type("string").append("enum", List.of("PUBLISH_FAILED", "AUTHORITY_DENIED", "PAYLOAD_UNAVAILABLE")));
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", List.of("_id", "schemaVersion", "receiptId", "rawCommitment", "eventId", "eventCreatedAt",
                        "createdAt", "expiresAt", "state", "attempts", "nextAttemptAt", "keyId", "nonce", "ciphertext"))
                .append("properties", properties));
    }

    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document reference() { return type("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"); }
    private static Document type(String value) { return new Document("bsonType", value); }
    private static IllegalStateException incompatible() { return new IllegalStateException("SOURCE_RAW_PUBLICATION_SCHEMA_INCOMPATIBLE"); }
}
