package com.frauddetection.common.events.source;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

/** Strict, immutable, receipt-bound terminal outcomes retained independently of the reusable customer gate. */
final class SourceRawTerminalDispositionSchema {
    static final String COLLECTION = "source_raw_terminal_dispositions";
    static final String READY_ID = "0".repeat(64);
    static final int MAX_DISPOSITIONS = 100_000;
    static final String LATE_REJECTION = "FEATURE_EVENT_TIME_OUT_OF_ORDER";
    private static final String GATES = SourceFeatureOrderGateSchema.COLLECTION;

    private SourceRawTerminalDispositionSchema() { }

    static void initialize(MongoDatabase database, boolean owner, Clock clock) {
        if (database == null || clock == null) { throw invalid("MIGRATION_INPUT_REQUIRED"); }
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        if (existing == null) {
            if (!owner) { throw invalid("OWNER_MIGRATION_REQUIRED"); }
            migrate(database, clock);
            return;
        }
        verifyCollection(database);
        verifyRecords(database);
        if (owner) { verifyLegacyGateCoverage(database); }
    }

    /** Explicit owner-run, resumable migration. It never changes an existing validator or index. */
    static void migrate(MongoDatabase database, Clock clock) {
        if (database == null || clock == null) { throw invalid("MIGRATION_INPUT_REQUIRED"); }
        Document existing = database.listCollections().filter(eq("name", COLLECTION)).first();
        if (existing == null) {
            if (database.listCollections().filter(eq("name", GATES)).first() == null) {
                throw invalid("ORDER_GATE_UNAVAILABLE");
            }
            database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                    .validator(validator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
        }
        verifyCollection(database);
        MongoCollection<Document> dispositions = database.getCollection(COLLECTION);
        List<Document> rejectedGates = database.getCollection(GATES).find(eq("status", "LATE_REJECTED"))
                .limit(MAX_DISPOSITIONS + 1).into(new ArrayList<>());
        if (rejectedGates.size() > MAX_DISPOSITIONS) { throw invalid("DISPOSITION_LIMIT_EXCEEDED"); }
        for (Document gate : rejectedGates) {
            String receiptId = gate.getString("receiptId");
            String rawCommitment = gate.getString("rawCommitment");
            Date decidedAt = gate.getDate("resolvedAt");
            if (!isHash(receiptId) || !isHash(rawCommitment)
                    || !SourceFeatureOrderGateSchema.isResolvedFor(gate, receiptId, rawCommitment)
                    || !"LATE_REJECTED".equals(gate.getString("status")) || decidedAt == null) {
                throw invalid("LEGACY_GATE_INVALID");
            }
            Document expected = disposition(receiptId, rawCommitment, decidedAt);
            Document current = dispositions.find(eq("_id", expected.getString("_id"))).first();
            if (current == null) { dispositions.insertOne(expected); }
            else if (!sameDisposition(current, expected)) { throw invalid("RECEIPT_COMMITMENT_CONFLICT"); }
        }
        long dispositionCount = dispositions.countDocuments(eq("recordType", "DISPOSITION"));
        if (dispositionCount > MAX_DISPOSITIONS) { throw invalid("DISPOSITION_LIMIT_EXCEEDED"); }
        dispositions.replaceOne(eq("_id", READY_ID), new Document("_id", READY_ID).append("schemaVersion", 1)
                .append("recordType", "READY").append("completedAt", Date.from(clock.instant()))
                .append("backfilledRows", rejectedGates.size()), new ReplaceOptions().upsert(true));
        verifyCollection(database);
        verifyRecords(database);
        verifyLegacyGateCoverage(database);
    }

    static Document disposition(String receiptId, String rawCommitment, Date decidedAt) {
        if (!isHash(receiptId) || !isHash(rawCommitment) || decidedAt == null) { throw invalid("DISPOSITION_INPUT_INVALID"); }
        String identity = identity(receiptId);
        if (READY_ID.equals(identity)) { throw invalid("DISPOSITION_ID_RESERVED"); }
        return new Document("_id", identity).append("schemaVersion", 1).append("recordType", "DISPOSITION")
                .append("receiptId", receiptId).append("rawCommitment", rawCommitment)
                .append("outcome", LATE_REJECTION).append("decidedAt", decidedAt);
    }

    static String identity(String receiptId) {
        if (!isHash(receiptId)) { throw invalid("RECEIPT_ID_INVALID"); }
        return SourceReceiptIdentity.digest("source-raw-terminal-disposition/v1", receiptId);
    }

    static boolean isExactDisposition(Document row, String receiptId, String rawCommitment) {
        return isHash(receiptId) && isHash(rawCommitment) && row != null
                && row.keySet().equals(Set.of("_id", "schemaVersion", "recordType", "receiptId",
                "rawCommitment", "outcome", "decidedAt"))
                && row.getInteger("schemaVersion", 0) == 1 && "DISPOSITION".equals(row.getString("recordType"))
                && identity(receiptId).equals(row.getString("_id")) && receiptId.equals(row.getString("receiptId"))
                && rawCommitment.equals(row.getString("rawCommitment")) && LATE_REJECTION.equals(row.getString("outcome"))
                && row.get("decidedAt") instanceof Date;
    }

    static boolean sameDisposition(Document actual, Document expected) {
        return actual != null && expected != null && actual.keySet().equals(expected.keySet())
                && expected.getString("_id").equals(actual.getString("_id"))
                && expected.getInteger("schemaVersion", 0) == actual.getInteger("schemaVersion", 0)
                && expected.getString("recordType").equals(actual.getString("recordType"))
                && expected.getString("receiptId").equals(actual.getString("receiptId"))
                && expected.getString("rawCommitment").equals(actual.getString("rawCommitment"))
                && expected.getString("outcome").equals(actual.getString("outcome"))
                && expected.getDate("decidedAt").equals(actual.getDate("decidedAt"));
    }

    private static void verifyLegacyGateCoverage(MongoDatabase database) {
        List<Document> rejectedGates = database.getCollection(GATES).find(eq("status", "LATE_REJECTED"))
                .limit(MAX_DISPOSITIONS + 1).into(new ArrayList<>());
        if (rejectedGates.size() > MAX_DISPOSITIONS) { throw invalid("DISPOSITION_LIMIT_EXCEEDED"); }
        for (Document gate : rejectedGates) {
            String receiptId = gate.getString("receiptId");
            String rawCommitment = gate.getString("rawCommitment");
            if (!isHash(receiptId) || !isHash(rawCommitment)
                    || !SourceFeatureOrderGateSchema.isResolvedFor(gate, receiptId, rawCommitment)
                    || !isExactDisposition(database.getCollection(COLLECTION)
                    .find(eq("_id", identity(receiptId))).first(), receiptId, rawCommitment)) {
                throw invalid("LEGACY_DISPOSITION_MISSING");
            }
        }
    }

    private static void verifyCollection(MongoDatabase database) {
        Document collection = database.listCollections().filter(eq("name", COLLECTION)).first();
        Document options = collection == null ? null : collection.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validator().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) { throw invalid("SCHEMA_INVALID"); }
        List<Document> indexes = database.getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
        if (indexes.size() != 1 || !"_id_".equals(indexes.getFirst().getString("name"))
                || !new Document("_id", 1).equals(indexes.getFirst().get("key"))
                || indexes.getFirst().containsKey("expireAfterSeconds")
                || indexes.getFirst().containsKey("partialFilterExpression")
                || indexes.getFirst().containsKey("collation") || Boolean.TRUE.equals(indexes.getFirst().get("sparse"))
                || Boolean.TRUE.equals(indexes.getFirst().get("hidden"))) { throw invalid("INDEX_INVALID"); }
    }

    private static void verifyRecords(MongoDatabase database) {
        boolean readySeen = false;
        int dispositions = 0;
        MongoCursor<Document> cursor = database.getCollection(COLLECTION).find().limit(MAX_DISPOSITIONS + 2).iterator();
        try (cursor) {
            while (cursor.hasNext()) {
                Document row = cursor.next();
                if (READY_ID.equals(row.getString("_id"))) {
                    if (readySeen || !row.keySet().equals(Set.of("_id", "schemaVersion", "recordType", "completedAt", "backfilledRows"))
                            || row.getInteger("schemaVersion", 0) != 1 || !"READY".equals(row.getString("recordType"))
                            || !(row.get("completedAt") instanceof Date) || !(row.get("backfilledRows") instanceof Integer count)
                            || count < 0 || count > MAX_DISPOSITIONS) { throw invalid("MIGRATION_INCOMPLETE"); }
                    readySeen = true;
                } else if (isDispositionRecord(row)) {
                    dispositions++;
                } else { throw invalid("DISPOSITION_INVALID"); }
            }
        }
        if (!readySeen) { throw invalid("MIGRATION_INCOMPLETE"); }
        if (dispositions > MAX_DISPOSITIONS) { throw invalid("DISPOSITION_LIMIT_EXCEEDED"); }
    }

    private static boolean isDispositionRecord(Document row) {
        String receiptId = row == null ? null : row.getString("receiptId");
        String rawCommitment = row == null ? null : row.getString("rawCommitment");
        return isHash(receiptId) && isHash(rawCommitment) && isExactDisposition(row, receiptId, rawCommitment);
    }

    private static Document validator() {
        Document common = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("recordType", type("string"));
        Document disposition = new Document(common).append("recordType", type("string").append("enum", List.of("DISPOSITION")))
                .append("receiptId", hash()).append("rawCommitment", hash())
                .append("outcome", type("string").append("enum", List.of(LATE_REJECTION))).append("decidedAt", type("date"));
        Document ready = new Document(common).append("recordType", type("string").append("enum", List.of("READY")))
                .append("completedAt", type("date")).append("backfilledRows", type("int")
                        .append("minimum", 0).append("maximum", MAX_DISPOSITIONS));
        Document dispositionSchema = schema(disposition, List.of("_id", "schemaVersion", "recordType", "receiptId",
                "rawCommitment", "outcome", "decidedAt"));
        Document readySchema = schema(ready, List.of("_id", "schemaVersion", "recordType", "completedAt", "backfilledRows"));
        return new Document("$jsonSchema", new Document("oneOf", List.of(dispositionSchema, readySchema)));
    }

    private static Document schema(Document properties, List<String> required) {
        return new Document("bsonType", "object").append("additionalProperties", false)
                .append("required", required).append("properties", properties);
    }
    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document type(Object value) { return new Document("bsonType", value); }
    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("SOURCE_RAW_TERMINAL_DISPOSITION_" + reason);
    }
}
