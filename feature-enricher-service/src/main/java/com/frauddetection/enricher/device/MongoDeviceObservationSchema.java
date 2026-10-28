package com.frauddetection.enricher.device;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.BsonType;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.not;
import static com.mongodb.client.model.Filters.type;

/** Owns the startup schema and index compatibility contract for the device observation store. */
final class MongoDeviceObservationSchema {
    private static final List<IndexSpec> CANDIDATE_INDEXES = List.of(
            index("candidate_source_event_unique", true, false, "institution", "source", "sourceEvent"),
            index("candidate_transaction_unique", true, false, "institution", "source", "transaction"),
            index("candidate_sequence_unique", true, false, "institution", "source", "sequence"),
            index("candidate_customer_event_status_sequence", false, false,
                    "institution", "source", "customer", "eventTime", "status", "sequence"),
            index("candidate_status_staged_at", false, false, "status", "stagedAt"),
            index("candidate_status_event_source_event", false, false,
                    "institution", "source", "status", "eventTime", "sourceEvent"),
            index("candidate_device_status", false, false, "institution", "source", "keyVersion", "device", "status"),
            index("candidate_terminal_expiry_ttl", false, true, "terminalExpireAt"));
    private static final List<IndexSpec> OBSERVATION_INDEXES = List.of(
            index("observation_device_window", false, false,
                    "institution", "source", "keyVersion", "device", "eventTime", "sourceEvent"),
            index("observation_customer_window", false, false,
                    "institution", "source", "keyVersion", "customer", "eventTime", "sourceEvent"),
            index("observation_expiry_ttl", false, true, "expireAt"));
    private static final List<IndexSpec> OCCURRENCE_INDEXES = List.of(
            index("occurrence_transaction_unique", true, false, "institution", "source", "transaction"),
            index("occurrence_device", false, false, "institution", "source", "keyVersion", "device"),
            index("occurrence_expiry_ttl", false, true, "expireAt"));
    private static final List<IndexSpec> TOMBSTONE_INDEXES = List.of(
            index("tombstone_expiry_ttl", false, true, "expireAt"));

    private final MongoDatabase database;

    MongoDeviceObservationSchema(MongoDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    void ensureIndexes() {
        MongoCollection<Document> candidates = database.getCollection("device_observation_candidates");
        MongoCollection<Document> observations = database.getCollection("device_observations");
        MongoCollection<Document> occurrences = database.getCollection("device_occurrences");
        MongoCollection<Document> coverage = database.getCollection("device_source_coverage");
        MongoCollection<Document> fences = database.getCollection("device_scope_fences");
        MongoCollection<Document> revocationTombstones =
                database.getCollection("device_operational_revocation_tombstones");

        Set<String> existingCollections = new HashSet<>(database.listCollectionNames().into(new ArrayList<>()));
        validateExistingSchema(existingCollections, candidates, observations, occurrences, coverage, fences,
                revocationTombstones);
        createIndexes(candidates, CANDIDATE_INDEXES);
        createIndexes(observations, OBSERVATION_INDEXES);
        createIndexes(occurrences, OCCURRENCE_INDEXES);
        createIndexes(revocationTombstones, TOMBSTONE_INDEXES);
    }

    private void validateExistingSchema(Set<String> existingCollections,
                                        MongoCollection<Document> candidates,
                                        MongoCollection<Document> observations,
                                        MongoCollection<Document> occurrences,
                                        MongoCollection<Document> coverage,
                                        MongoCollection<Document> fences,
                                        MongoCollection<Document> revocationTombstones) {
        validateCollection(existingCollections, "device_observation_candidates", candidates,
                CANDIDATE_INDEXES, "expireAt");
        validateCollection(existingCollections, "device_observations", observations, OBSERVATION_INDEXES);
        validateCollection(existingCollections, "device_occurrences", occurrences, OCCURRENCE_INDEXES);
        validateCollection(existingCollections, "device_source_coverage", coverage, List.of(), "pendingCounterVersion");
        validateCollection(existingCollections, "device_scope_fences", fences, List.of());
        if (existingCollections.contains("device_erasure_tombstones")) {
            throw schemaIncompatible();
        }
        validateCollection(existingCollections, "device_operational_revocation_tombstones", revocationTombstones,
                TOMBSTONE_INDEXES);
        if (candidates.find(and(eq("status", "STAGED"), not(type("stagedAt", BsonType.DATE_TIME))))
                .limit(1).first() != null) {
            throw schemaIncompatible();
        }
    }

    private static void createIndexes(MongoCollection<Document> collection, List<IndexSpec> indexes) {
        for (IndexSpec index : indexes) {
            collection.createIndex(index.keys(), index.options());
        }
    }

    private static void validateCollection(Set<String> existingCollections, String collectionName,
                                           MongoCollection<Document> collection, List<IndexSpec> expectedIndexes,
                                           String... forbiddenFields) {
        if (!existingCollections.contains(collectionName)) {
            return;
        }
        List<Document> actualIndexes = collection.listIndexes().into(new ArrayList<>());
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add("_id_");
        for (IndexSpec expected : expectedIndexes) {
            expectedNames.add(expected.name());
        }
        Set<String> actualNames = new HashSet<>();
        for (Document actual : actualIndexes) {
            String name = actual.getString("name");
            if (name == null || !actualNames.add(name)) {
                throw schemaIncompatible();
            }
        }
        if (!actualNames.equals(expectedNames)) {
            throw schemaIncompatible();
        }
        for (IndexSpec expected : expectedIndexes) {
            Document actual = actualIndexes.stream()
                    .filter(index -> expected.name().equals(index.getString("name")))
                    .findFirst()
                    .orElseThrow(MongoDeviceObservationSchema::schemaIncompatible);
            if (!expected.matches(actual)) {
                throw schemaIncompatible();
            }
        }
        for (String forbiddenField : forbiddenFields) {
            if (collection.find(exists(forbiddenField, true)).limit(1).first() != null) {
                throw schemaIncompatible();
            }
        }
    }

    private static IndexSpec index(String name, boolean unique, boolean ttl, String... fields) {
        Document keys = new Document();
        for (String field : fields) {
            keys.append(field, 1);
        }
        return new IndexSpec(name, keys, unique, ttl);
    }

    private static IllegalStateException schemaIncompatible() {
        return new IllegalStateException("DEVICE_STORE_SCHEMA_INCOMPATIBLE");
    }

    private record IndexSpec(String name, Document keys, boolean unique, boolean ttl) {
        private IndexOptions options() {
            IndexOptions options = new IndexOptions().name(name);
            if (unique) {
                options.unique(true);
            }
            if (ttl) {
                options.expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS);
            }
            return options;
        }

        private boolean matches(Document actual) {
            if (!sameOrderedKeys(keys, actual.get("key", Document.class))
                    || unique != Boolean.TRUE.equals(actual.get("unique"))
                    || actual.containsKey("partialFilterExpression")
                    || Boolean.TRUE.equals(actual.getBoolean("sparse"))
                    || Boolean.TRUE.equals(actual.getBoolean("hidden"))
                    || !hasSimpleCollation(actual)) {
                return false;
            }
            if (!unique && actual.containsKey("unique")) {
                return false;
            }
            if (!ttl) {
                return !actual.containsKey("expireAfterSeconds");
            }
            Object expiry = actual.get("expireAfterSeconds");
            return expiry instanceof Number number && number.longValue() == 0L;
        }

        private static boolean sameOrderedKeys(Document expected, Document actual) {
            if (actual == null || expected.size() != actual.size()) {
                return false;
            }
            var expectedEntries = expected.entrySet().iterator();
            var actualEntries = actual.entrySet().iterator();
            while (expectedEntries.hasNext()) {
                var expectedEntry = expectedEntries.next();
                var actualEntry = actualEntries.next();
                if (!expectedEntry.getKey().equals(actualEntry.getKey())
                        || !Objects.equals(expectedEntry.getValue(), actualEntry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hasSimpleCollation(Document actual) {
            Document collation = actual.get("collation", Document.class);
            return collation == null || "simple".equals(collation.getString("locale"));
        }
    }
}
