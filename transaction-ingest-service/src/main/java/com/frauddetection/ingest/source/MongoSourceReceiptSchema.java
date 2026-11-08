package com.frauddetection.ingest.source;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.BsonRegularExpression;
import org.bson.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

/** Fresh bootstrap or exact verification only; never repairs, drops or relaxes an existing schema. */
final class MongoSourceReceiptSchema {
    static final String IDENTITIES = "source_admission_identities";
    static final String RECEIPTS = "source_admission_receipts";
    static final String LIMITS = "source_admission_limits";
    static final Document IDENTITY_INDEX = new Document("issuer", 1).append("institution", 1).append("source", 1)
            .append("sourceEpoch", 1).append("sourceEventId", 1);
    static final Document EXPIRY_INDEX = new Document("state", 1).append("expiresAt", 1);
    static final Document CAPACITY_INDEX = new Document("capacitySlot", 1);

    static boolean initialize(MongoDatabase database) {
        Set<String> expected = Set.of(IDENTITIES, RECEIPTS, LIMITS);
        Document discovery = database.runCommand(new Document("listCollections", 1)
                .append("filter", new Document("name", new BsonRegularExpression("^source_admission_")))
                .append("cursor", new Document("batchSize", 4)));
        Document cursor = discovery.get("cursor", Document.class);
        List<Document> batch = cursor == null ? null : cursor.getList("firstBatch", Document.class);
        if (batch == null || !(cursor.get("id") instanceof Number id) || id.longValue() != 0L || batch.size() > 3) {
            throw incompatible();
        }
        Set<String> present = new HashSet<>();
        batch.forEach(collection -> present.add(collection.getString("name")));
        boolean fresh = present.isEmpty();
        if (!fresh && !present.equals(expected)) { throw incompatible(); }
        if (fresh) {
            for (String name : List.of(IDENTITIES, RECEIPTS, LIMITS)) {
                database.createCollection(name, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                        .validator(validator(name)).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
            }
            database.getCollection(IDENTITIES).createIndex(IDENTITY_INDEX,
                    new IndexOptions().name("source_identity_unique").unique(true));
            database.getCollection(IDENTITIES).createIndex(EXPIRY_INDEX, new IndexOptions().name("source_receipt_expiry"));
            database.getCollection(IDENTITIES).createIndex(CAPACITY_INDEX, new IndexOptions().name("source_capacity_slot_unique").unique(true));
        }
        for (String name : expected) {
            Document collection = database.listCollections().filter(eq("name", name)).first();
            Document options = collection == null ? null : collection.get("options", Document.class);
            if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                    || !validator(name).equals(options.get("validator"))
                    || !"strict".equals(options.getString("validationLevel"))
                    || !"error".equals(options.getString("validationAction"))) {
                throw incompatible();
            }
            Set<String> indexes = new HashSet<>();
            try (var iterator = database.getCollection(name).listIndexes().iterator()) {
                while (iterator.hasNext()) {
                    Document index = iterator.next();
                    String indexName = index.getString("name");
                    if (indexes.size() >= (IDENTITIES.equals(name) ? 4 : 1) || !indexes.add(indexName) || index.containsKey("expireAfterSeconds")
                            || index.containsKey("partialFilterExpression") || index.containsKey("collation")
                            || Boolean.TRUE.equals(index.get("sparse")) || Boolean.TRUE.equals(index.get("hidden"))) { throw incompatible(); }
                    Document key = index.get("key", Document.class);
                    boolean valid = "_id_".equals(indexName) && sameKey(new Document("_id", 1), key)
                            || IDENTITIES.equals(name) && "source_identity_unique".equals(indexName)
                            && sameKey(IDENTITY_INDEX, key) && Boolean.TRUE.equals(index.get("unique"))
                            || IDENTITIES.equals(name) && "source_receipt_expiry".equals(indexName)
                            && sameKey(EXPIRY_INDEX, key) && !Boolean.TRUE.equals(index.get("unique"))
                            || IDENTITIES.equals(name) && "source_capacity_slot_unique".equals(indexName)
                            && sameKey(CAPACITY_INDEX, key) && Boolean.TRUE.equals(index.get("unique"));
                    if (!valid) { throw incompatible(); }
                }
            }
            if (!indexes.equals(IDENTITIES.equals(name)
                    ? Set.of("_id_", "source_identity_unique", "source_receipt_expiry", "source_capacity_slot_unique") : Set.of("_id_"))) {
                throw incompatible();
            }
        }
        return fresh;
    }

    private static Document validator(String name) {
        Document fields = new Document("_id", LIMITS.equals(name) ? type("string").append("enum", List.of("capacity")) : hash())
                .append("schemaVersion", type("int").append("enum", List.of(1)));
        if (LIMITS.equals(name)) {
            fields.append("policyCommitment", hash()).append("count", type("int").append("minimum", 0).append("maximum", 100_000));
        } else {
            fields.append("bindingCommitment", hash()).append("admittedAt", type("date")).append("expiresAt", type("date"));
            if (IDENTITIES.equals(name)) {
                for (String field : List.of("issuer", "institution", "source", "sourceEpoch", "sourceEventId")) {
                    fields.append(field, reference());
                }
                fields.append("state", type("string").append("enum", List.of("ADMITTED", "CONFLICT", "TOMBSTONED")))
                        .append("capacitySlot", type("int").append("minimum", 1).append("maximum", 100_000));
            } else {
                for (String field : List.of("signingKeyVersion", "purpose", "audience", "policyVersion")) {
                    fields.append(field, reference());
                }
                fields.append("eventTime", type("string").append("maxLength", 64))
                        .append("verifiedAt", type("string").append("maxLength", 64))
                        .append("payloadCommitment", hash()).append("keyCommitment", hash());
            }
        }
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", List.copyOf(fields.keySet())).append("properties", fields));
    }

    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static boolean sameKey(Document expected, Document actual) {
        return expected.equals(actual) && List.copyOf(expected.keySet()).equals(List.copyOf(actual.keySet()));
    }
    private static Document reference() { return type("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"); }
    private static Document type(String type) { return new Document("bsonType", type); }
    private static IllegalStateException incompatible() { return new IllegalStateException("SOURCE_RECEIPT_SCHEMA_INCOMPATIBLE"); }
}
