package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;

/** Durable, privacy-preserving uniqueness fence for attested business transaction identities. */
final class SourceEnrichedBusinessIdentitySchema {
    static final String COLLECTION = "source_enriched_business_identities";
    static final String EXPIRY_INDEX = "source_business_identity_expiry";
    static final String READY_ID = "0".repeat(64);
    static final int MAX_BACKFILL_ROWS = 100_000;
    private static final String PUBLICATIONS = SourceEnrichedPublicationCipher.COLLECTION;
    private static final String IDENTITIES = "source_admission_identities";
    private static final String RECEIPTS = "source_admission_receipts";
    private static final String RAW = "source_lineage_raw";

    private SourceEnrichedBusinessIdentitySchema() { }

    static void initialize(MongoDatabase database, boolean owner) {
        ensureCollection(database, owner);
        verifyCollection(database);
        Document ready = database.getCollection(COLLECTION).find(eq("_id", READY_ID)).first();
        if (ready == null || ready.getInteger("schemaVersion", 0) != 1 || !"READY".equals(ready.getString("recordType"))
                || !(ready.get("completedAt") instanceof Date) || !(ready.get("backfilledRows") instanceof Integer rows)
                || rows < 0 || rows > MAX_BACKFILL_ROWS) {
            throw incompatible("MIGRATION_INCOMPLETE");
        }
        verifyRecords(database);
    }

    /** Owner-only forward migration; bounded to the maximum source admission capacity. */
    static void migrate(MongoDatabase database, SourceEnrichedPublicationCipher cipher, Clock clock) {
        if (database == null || cipher == null || clock == null) { throw incompatible("MIGRATION_INPUT_REQUIRED"); }
        ensureCollection(database, true);
        verifyCollection(database);
        Instant now = clock.instant();
        List<Document> publications = database.getCollection(PUBLICATIONS).find(gt("expiresAt", Date.from(now)))
                .sort(new Document("_id", 1)).limit(MAX_BACKFILL_ROWS + 1).into(new ArrayList<>());
        if (publications.size() > MAX_BACKFILL_ROWS) { throw incompatible("BACKFILL_LIMIT_EXCEEDED"); }

        var fences = database.getCollection(COLLECTION);
        int backfilledRows = 0;
        for (Document publication : publications) {
            Document fence = fenceFor(database, cipher, publication);
            Document existing = fences.find(eq("_id", fence.getString("_id"))).first();
            if (existing == null) {
                fences.insertOne(fence);
            } else if (!sameFence(existing, fence)) {
                throw incompatible("BUSINESS_IDENTITY_COLLISION");
            }
            backfilledRows++;
        }
        fences.replaceOne(eq("_id", READY_ID), new Document("_id", READY_ID).append("schemaVersion", 1)
                .append("recordType", "READY").append("completedAt", Date.from(now)).append("backfilledRows", backfilledRows),
                new ReplaceOptions().upsert(true));
        initialize(database, false);
    }

    static Document newFence(String identity, String receiptId, String rawCommitment,
                              String enrichedCommitment, Date expiresAt) {
        if (identity == null || !identity.matches("[0-9a-f]{64}") || READY_ID.equals(identity)
                || !isHash(receiptId) || !isHash(rawCommitment) || !isHash(enrichedCommitment) || expiresAt == null) {
            throw incompatible("FENCE_INPUT_INVALID");
        }
        return new Document("_id", identity).append("schemaVersion", 1).append("recordType", "FENCE")
                .append("receiptId", receiptId).append("rawCommitment", rawCommitment)
                .append("enrichedCommitment", enrichedCommitment).append("expiresAt", expiresAt);
    }

    static boolean sameFence(Document existing, Document expected) {
        return existing != null && expected != null && existing.getInteger("schemaVersion", 0) == 1
                && "FENCE".equals(existing.getString("recordType"))
                && expected.getString("_id").equals(existing.getString("_id"))
                && expected.getString("receiptId").equals(existing.getString("receiptId"))
                && expected.getString("rawCommitment").equals(existing.getString("rawCommitment"))
                && expected.getString("enrichedCommitment").equals(existing.getString("enrichedCommitment"))
                && expected.getDate("expiresAt").equals(existing.getDate("expiresAt"));
    }

    private static Document fenceFor(MongoDatabase database, SourceEnrichedPublicationCipher cipher, Document publication) {
        try {
            String receiptId = publication.getString("receiptId");
            String rawCommitment = publication.getString("rawCommitment");
            String enrichedCommitment = publication.getString("enrichedCommitment");
            Date expiresAt = publication.getDate("expiresAt");
            TransactionEnrichedEvent event = cipher.decrypt(publication);
            if (!isHash(receiptId) || !isHash(rawCommitment) || !isHash(enrichedCommitment) || expiresAt == null
                    || event == null || !enrichedCommitment.equals(SourceEventCommitment.enriched(event))
                    || !event.eventId().equals(publication.getString("eventId"))
                    || !event.createdAt().toString().equals(publication.getString("eventCreatedAt"))) {
                throw incompatible("PUBLICATION_BINDING_INVALID");
            }
            Document identity = database.getCollection(IDENTITIES).find(eq("_id", receiptId)).first();
            Document receipt = database.getCollection(RECEIPTS).find(eq("_id", receiptId)).first();
            if (identity == null || receipt == null || identity.getInteger("schemaVersion", 0) != 1
                    || receipt.getInteger("schemaVersion", 0) != 1
                    || !expiresAt.equals(identity.getDate("expiresAt")) || !expiresAt.equals(receipt.getDate("expiresAt"))) {
                throw incompatible("RECEIPT_BINDING_INVALID");
            }
            SourceScope scope = new SourceScope(identity.getString("issuer"), identity.getString("institution"),
                    identity.getString("source"), identity.getString("sourceEpoch"), receipt.getString("signingKeyVersion"),
                    identity.getString("sourceEventId"), canonicalTime(receipt.getString("eventTime")),
                    receipt.getString("purpose"), receipt.getString("audience"), receipt.getString("payloadCommitment"));
            String binding = SourceReceiptIdentity.binding(scope, receipt.getString("keyCommitment"));
            if (!SourceReceiptIdentity.identity(scope).equals(receiptId)
                    || !binding.equals(identity.getString("bindingCommitment"))
                    || !binding.equals(receipt.getString("bindingCommitment"))) {
                throw incompatible("RECEIPT_BINDING_INVALID");
            }
            Document rawLink = database.getCollection(RAW).find(eq("eventCommitment", rawCommitment)).first();
            if (rawLink == null || !receiptId.equals(rawLink.getString("receiptId"))
                    || !expiresAt.equals(rawLink.getDate("expiresAt"))) {
                throw incompatible("RAW_BINDING_INVALID");
            }
            String businessIdentity = SourceEventCommitment.businessTransaction(scope, event.customerId(), event.transactionId());
            return newFence(businessIdentity, receiptId, rawCommitment, enrichedCommitment, expiresAt);
        } catch (RuntimeException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("SOURCE_BUSINESS_IDENTITY_MIGRATION_")) {
                throw invalid;
            }
            throw incompatible("PUBLICATION_UNAVAILABLE");
        }
    }

    private static Instant canonicalTime(String value) {
        Instant parsed = Instant.parse(value);
        if (!parsed.toString().equals(value)) { throw incompatible("RECEIPT_BINDING_INVALID"); }
        return parsed;
    }

    private static void ensureCollection(MongoDatabase database, boolean owner) {
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        if (existing != null) {
            if (owner && hasExpectedValidator(existing)) {
                var indexes = database.getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
                if (indexes.size() == 1 && isIdIndex(indexes.getFirst())) {
                    database.getCollection(COLLECTION).createIndex(new Document("expiresAt", 1),
                            new IndexOptions().name(EXPIRY_INDEX).expireAfter(0L, TimeUnit.SECONDS));
                }
            }
            return;
        }
        if (!owner) { throw incompatible("OWNER_MIGRATION_REQUIRED"); }
        database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                .validator(validator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
        database.getCollection(COLLECTION).createIndex(new Document("expiresAt", 1),
                new IndexOptions().name(EXPIRY_INDEX).expireAfter(0L, TimeUnit.SECONDS));
    }

    private static void verifyCollection(MongoDatabase database) {
        Document collection = database.listCollections().filter(eq("name", COLLECTION)).first();
        Document options = collection == null ? null : collection.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validator().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) {
            throw incompatible("SCHEMA_INVALID");
        }
        List<Document> indexes = database.getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
        boolean id = indexes.stream().anyMatch(index -> "_id_".equals(index.getString("name"))
                && new Document("_id", 1).equals(index.get("key")));
        Document expiry = indexes.stream().filter(index -> EXPIRY_INDEX.equals(index.getString("name"))).findFirst().orElse(null);
        if (indexes.size() != 2 || !id || expiry == null || !new Document("expiresAt", 1).equals(expiry.get("key"))
                || !(expiry.get("expireAfterSeconds") instanceof Number seconds) || seconds.intValue() != 0
                || Boolean.TRUE.equals(expiry.get("sparse")) || Boolean.TRUE.equals(expiry.get("hidden"))
                || expiry.containsKey("partialFilterExpression") || expiry.containsKey("collation")) {
            throw incompatible("INDEX_INVALID");
        }
    }

    private static boolean hasExpectedValidator(Document collection) {
        Document options = collection.get("options", Document.class);
        return options != null && options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                && validator().equals(options.get("validator")) && "strict".equals(options.getString("validationLevel"))
                && "error".equals(options.getString("validationAction"));
    }

    private static boolean isIdIndex(Document index) {
        return index != null && "_id_".equals(index.getString("name")) && new Document("_id", 1).equals(index.get("key"));
    }

    /** Existing BSON rows are not retroactively checked by Mongo's strict collection validator. */
    private static void verifyRecords(MongoDatabase database) {
        int count = 0;
        boolean readySeen = false;
        try (MongoCursor<Document> cursor = database.getCollection(COLLECTION).find().limit(MAX_BACKFILL_ROWS + 2).iterator()) {
            while (cursor.hasNext()) {
                Document row = cursor.next();
                if (++count > MAX_BACKFILL_ROWS + 1) { throw incompatible("RECORD_LIMIT_EXCEEDED"); }
                if (READY_ID.equals(row.getString("_id"))) {
                    if (readySeen || !isReadyRecord(row)) { throw incompatible("MIGRATION_INCOMPLETE"); }
                    readySeen = true;
                } else if (!isFenceRecord(row)) {
                    throw incompatible("FENCE_INVALID");
                }
            }
        }
        if (!readySeen) { throw incompatible("MIGRATION_INCOMPLETE"); }
    }

    private static boolean isReadyRecord(Document row) {
        return row != null && row.keySet().equals(Set.of("_id", "schemaVersion", "recordType", "completedAt", "backfilledRows"))
                && READY_ID.equals(row.getString("_id")) && row.getInteger("schemaVersion", 0) == 1
                && "READY".equals(row.getString("recordType")) && row.get("completedAt") instanceof Date
                && row.get("backfilledRows") instanceof Integer rows && rows >= 0 && rows <= MAX_BACKFILL_ROWS;
    }

    private static boolean isFenceRecord(Document row) {
        return row != null && row.keySet().equals(Set.of("_id", "schemaVersion", "recordType", "receiptId", "rawCommitment",
                "enrichedCommitment", "expiresAt")) && isHash(row.getString("_id")) && !READY_ID.equals(row.getString("_id"))
                && row.getInteger("schemaVersion", 0) == 1 && "FENCE".equals(row.getString("recordType"))
                && isHash(row.getString("receiptId")) && isHash(row.getString("rawCommitment"))
                && isHash(row.getString("enrichedCommitment")) && row.get("expiresAt") instanceof Date;
    }

    private static Document validator() {
        Document common = new Document("_id", type("string").append("pattern", "^[0-9a-f]{64}$"))
                .append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("recordType", type("string"));
        Document fence = new Document(common).append("recordType", type("string").append("enum", List.of("FENCE")))
                .append("receiptId", hash()).append("rawCommitment", hash()).append("enrichedCommitment", hash())
                .append("expiresAt", type("date"));
        Document ready = new Document(common).append("recordType", type("string").append("enum", List.of("READY")))
                .append("completedAt", type("date")).append("backfilledRows", type("int")
                        .append("minimum", 0).append("maximum", MAX_BACKFILL_ROWS));
        Document fenceSchema = new Document("bsonType", "object").append("additionalProperties", false)
                .append("required", List.of("_id", "schemaVersion", "recordType", "receiptId", "rawCommitment",
                        "enrichedCommitment", "expiresAt")).append("properties", fence);
        Document readySchema = new Document("bsonType", "object").append("additionalProperties", false)
                .append("required", List.of("_id", "schemaVersion", "recordType", "completedAt", "backfilledRows"))
                .append("properties", ready);
        return new Document("$jsonSchema", new Document("oneOf", List.of(fenceSchema, readySchema)));
    }

    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document type(Object value) { return new Document("bsonType", value); }
    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static IllegalStateException incompatible(String reason) {
        return new IllegalStateException("SOURCE_BUSINESS_IDENTITY_MIGRATION_" + reason);
    }
}
