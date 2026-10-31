package com.frauddetection.enricher.merchant;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

/** Fresh bootstrap only. Existing collections must match; nothing is migrated, dropped, or repaired. */
final class MongoMerchantObservationSchema {
    static final int SCHEMA_VERSION = 3;
    static final String CANDIDATES = "merchant_observation_candidates";
    static final String HISTORY = "merchant_observations";
    static final String MANIFEST = "merchant_observation_manifest";
    static final String COVERAGE = "merchant_source_coverage";
    static final String MEMBERSHIP = "merchant_observation_membership";
    static final String CANDIDATE_WINDOW = "merchant_candidate_window";
    static final String HISTORY_WINDOW = "merchant_history_window";
    static final String MANIFEST_WINDOW = "merchant_manifest_window";
    static final String MEMBERSHIP_WINDOW = "merchant_membership_window";
    private static final List<IndexSpec> CANDIDATE_INDEXES = List.of(
            index("merchant_event_unique", true, false, "institution", "source", "sourceEvent"),
            index("merchant_transaction_unique", true, false, "institution", "source", "transaction"),
            index("merchant_occurrence_unique", true, false, "institution", "source", "occurrence"),
            index("merchant_sequence_unique", true, false, "institution", "source", "epoch", "sequence"),
            index(CANDIDATE_WINDOW, false, false, "scope", "merchant", "status", "eventTime", "sequence"),
            index("merchant_seal_batch", false, false, "scope", "sequence"));
    private static final List<IndexSpec> HISTORY_INDEXES = List.of(
            index(HISTORY_WINDOW, false, false, "scope", "merchant", "eventTime", "sequence"),
            index("merchant_history_ttl", false, true, "expireAt"));
    private static final List<IndexSpec> MANIFEST_INDEXES = List.of(
            index("merchant_manifest_event_unique", true, false, "institution", "source", "sourceEvent"),
            index("merchant_manifest_transaction_unique", true, false, "institution", "source", "transaction"),
            index("merchant_manifest_occurrence_unique", true, false, "institution", "source", "occurrence"),
            index("merchant_manifest_sequence_unique", true, false, "institution", "source", "epoch", "sequence"),
            index(MANIFEST_WINDOW, false, false, "scope", "merchant", "eventTime", "sequence"),
            index("merchant_manifest_sequence", false, false, "scope", "sequence"));
    private static final List<IndexSpec> MEMBERSHIP_INDEXES = List.of(
            index(MEMBERSHIP_WINDOW, false, false, "scope", "merchant", "eventTime", "sequence"),
            index("merchant_membership_sequence_unique", true, false, "institution", "source", "epoch", "sequence"));

    static void initialize(MongoDatabase database) {
        Set<String> expected = Set.of(CANDIDATES, HISTORY, MANIFEST, COVERAGE, MEMBERSHIP);
        Set<String> names = new HashSet<>();
        try (var cursor = database.listCollectionNames().batchSize(33).iterator()) {
            while (cursor.hasNext()) {
                if (names.size() >= 32) { throw incompatible(); }
                String name = cursor.next();
                if (name.startsWith("merchant_") && !expected.contains(name)) { throw incompatible(); }
                names.add(name);
            }
        }
        long present = expected.stream().filter(names::contains).count();
        if (present != 0 && present != expected.size()) {
            throw incompatible();
        }
        if (present == 0) {
            for (String name : expected) {
                database.createCollection(name, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                        .validator(validator(name)).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
            }
            createIndexes(database, CANDIDATES, CANDIDATE_INDEXES);
            createIndexes(database, HISTORY, HISTORY_INDEXES);
            createIndexes(database, MANIFEST, MANIFEST_INDEXES);
            createIndexes(database, MEMBERSHIP, MEMBERSHIP_INDEXES);
        } else {
            for (String name : expected) {
                Document metadata = database.listCollections().filter(eq("name", name)).first();
                Document options = metadata == null ? null : metadata.get("options", Document.class);
                if (options == null || !validator(name).equals(options.get("validator"))
                        || !"strict".equals(options.getString("validationLevel"))
                        || !"error".equals(options.getString("validationAction"))
                        || Boolean.TRUE.equals(options.get("capped")) || options.containsKey("timeseries") || options.containsKey("clusteredIndex")
                        || options.containsKey("collation")
                        && !"simple".equals(options.get("collation", Document.class).getString("locale"))) {
                    throw incompatible();
                }
            }
            verifyIndexes(database, CANDIDATES, CANDIDATE_INDEXES);
            verifyIndexes(database, HISTORY, HISTORY_INDEXES);
            verifyIndexes(database, MANIFEST, MANIFEST_INDEXES);
            verifyIndexes(database, COVERAGE, List.of());
            verifyIndexes(database, MEMBERSHIP, MEMBERSHIP_INDEXES);
        }
    }

    private static Document validator(String name) {
        Document properties = new Document("_id", type("string").append("maxLength", 265)).append("schemaVersion", type("int").append("enum", List.of(SCHEMA_VERSION)))
                .append("issuer", token()).append("institution", token()).append("source", token()).append("epoch", token())
                .append("identityVersion", type("int").append("minimum", 1).append("maximum", 65_535));
        List<String> required = new ArrayList<>(List.of("_id", "schemaVersion", "issuer", "institution", "source", "epoch", "identityVersion"));
        if (COVERAGE.equals(name)) {
            properties.append("revision", type("long").append("minimum", 0))
                    .append("accepted", type("int").append("minimum", 0).append("maximum", 100_000))
                    .append("pending", type("int").append("minimum", 0).append("maximum", 100_000))
                    .append("quarantined", type("int").append("minimum", 0).append("maximum", 100_000))
                    .append("sealedSequence", type("long").append("minimum", 0))
                    .append("compactedThroughSequence", type("long").append("minimum", 0))
                    .append("retainedFrom", type("date"))
                    .append("invalidated", type("bool")).append("coverageStart", type("date")).append("watermark", type("date"))
                    .append("policyCommitment", token());
            required.addAll(List.of("revision", "accepted", "pending", "quarantined", "sealedSequence", "compactedThroughSequence",
                    "retainedFrom", "invalidated", "policyCommitment"));
        } else if (MEMBERSHIP.equals(name)) {
            properties.append("scope", type("string").append("maxLength", 265)).append("sourceEvent", token())
                    .append("merchant", token()).append("sequence", type("long").append("minimum", 1))
                    .append("eventTime", type("date")).append("inputCommitment", token())
                    .append("previousCheckpointId", new Document("bsonType", List.of("string", "null")).append("maxLength", 265))
                    .append("previousSequence", new Document("bsonType", List.of("long", "null")).append("minimum", 1))
                    .append("previousEventTime", new Document("bsonType", List.of("date", "null")))
                    .append("previousCommitment", new Document("bsonType", List.of("string", "null")).append("pattern", "^[0-9a-f]{64}$"))
                    .append("membershipCommitment", token());
            required.addAll(List.of("scope", "sourceEvent", "merchant", "sequence", "eventTime", "inputCommitment",
                    "previousCheckpointId", "previousSequence", "previousEventTime", "previousCommitment", "membershipCommitment"));
        } else {
            properties.append("scope", type("string").append("maxLength", 265)).append("sourceEvent", token()).append("transaction", token())
                    .append("occurrence", token()).append("merchant", token()).append("sequence", type("long").append("minimum", 1))
                    .append("eventTime", type("date")).append("sourceObservedAt", type("date"))
                    .append("payloadCommitment", token()).append("inputCommitment", token());
            required.addAll(List.of("scope", "sourceEvent", "transaction", "occurrence", "merchant", "sequence", "eventTime",
                    "sourceObservedAt", "payloadCommitment", "inputCommitment"));
            if (HISTORY.equals(name)) {
                properties.append("expireAt", type("date"));
                required.add("expireAt");
            } else if (CANDIDATES.equals(name)) {
                properties.append("status", type("string").append("enum", List.of("STAGED", "COMMITTED", "QUARANTINED")))
                        .append("stagedAt", type("date")).append("receiptExpireAt", type("date"))
                        .append("windowStart", type("date")).append("asOf", type("date"))
                        .append("availability", type("string").append("enum", List.of("COMPLETE_WINDOW", "UNAVAILABLE")))
                        .append("priorCount", new Document("bsonType", List.of("int", "null")))
                        .append("reason", type("string").append("enum", java.util.Arrays.stream(MerchantObservationStore.Reason.values()).map(Enum::name).toList()))
                        .append("snapshotCommitment", token()).append("terminalReason", type("string").append("maxLength", 64));
                required.addAll(List.of("status", "stagedAt"));
            }
        }
        return new Document("$jsonSchema", new Document("bsonType", "object").append("required", required)
                .append("additionalProperties", false).append("properties", properties));
    }

    private static Document type(String type) { return new Document("bsonType", type); }
    private static Document token() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }

    private static void createIndexes(MongoDatabase database, String name, List<IndexSpec> specs) {
        for (IndexSpec spec : specs) {
            IndexOptions options = new IndexOptions().name(spec.name());
            if (spec.unique()) { options.unique(true); }
            if (spec.ttl()) { options.expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS); }
            database.getCollection(name).createIndex(spec.keys(), options);
        }
    }

    private static void verifyIndexes(MongoDatabase database, String name, List<IndexSpec> specs) {
        List<Document> actual = new ArrayList<>();
        try (var cursor = database.getCollection(name).listIndexes().batchSize(specs.size() + 2).iterator()) {
            while (cursor.hasNext()) {
                if (actual.size() >= specs.size() + 1) { throw incompatible(); }
                actual.add(cursor.next());
            }
        }
        Set<String> expectedNames = new HashSet<>(Set.of("_id_"));
        specs.forEach(spec -> expectedNames.add(spec.name()));
        Set<String> actualNames = new HashSet<>();
        actual.forEach(index -> actualNames.add(index.getString("name")));
        if (actual.size() != actualNames.size() || !expectedNames.equals(actualNames)) {
            throw incompatible();
        }
        for (IndexSpec spec : specs) {
            Document found = actual.stream().filter(index -> spec.name().equals(index.getString("name"))).findFirst().orElseThrow();
            Document keys = found.get("key", Document.class);
            Document collation = found.get("collation", Document.class);
            if (keys == null || !new ArrayList<>(spec.keys().entrySet()).equals(new ArrayList<>(keys.entrySet()))
                    || spec.unique() != Boolean.TRUE.equals(found.get("unique"))
                    || found.containsKey("partialFilterExpression") || Boolean.TRUE.equals(found.get("sparse"))
                    || Boolean.TRUE.equals(found.get("hidden")) || collation != null && !"simple".equals(collation.getString("locale"))
                    || spec.ttl() && (!(found.get("expireAfterSeconds") instanceof Number n) || n.longValue() != 0)
                    || !spec.ttl() && found.containsKey("expireAfterSeconds")) {
                throw incompatible();
            }
        }
    }

    private static IndexSpec index(String name, boolean unique, boolean ttl, String... fields) {
        Document keys = new Document();
        for (String field : fields) { keys.append(field, 1); }
        return new IndexSpec(name, keys, unique, ttl);
    }

    private record IndexSpec(String name, Document keys, boolean unique, boolean ttl) { }
    private static IllegalStateException incompatible() { return new IllegalStateException("MERCHANT_STORE_SCHEMA_INCOMPATIBLE"); }
}
