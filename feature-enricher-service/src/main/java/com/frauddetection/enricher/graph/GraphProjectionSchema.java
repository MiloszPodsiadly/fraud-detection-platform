package com.frauddetection.enricher.graph;

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
import java.util.Objects;
import java.util.Set;

/** Immutable v1 schema contract. Runtime validation is read-only; bootstrap is empty-db-only. */
final class GraphProjectionSchema {
    static final int SCHEMA_VERSION = 1;
    static final String COLLECTION = "graph_edges";
    static final String SCHEMA_OWNER = "graph_schema_owner";
    static final String WRITER = "graph_projection_writer";
    static final String READER = "graph_private_reader";

    private static final List<IndexSpec> INDEXES = List.of(
            new IndexSpec("graph_logical_relation_unique", true,
                    keys("scope.sourceIssuerToken", "scope.institutionToken", "scope.sourceToken",
                            "scope.sourceEpochToken", "scope.identityVersion", "edgeType", "occurrence.token",
                            "device.token", "device.keyVersion")),
            new IndexSpec("graph_device_neighbors", false,
                    keys("scope.sourceIssuerToken", "scope.institutionToken", "scope.sourceToken",
                            "scope.sourceEpochToken", "scope.identityVersion", "edgeType", "device.token",
                            "device.keyVersion", "occurrence.eventTime", "occurrence.token")),
            new IndexSpec("graph_occurrence_neighbors", false,
                    keys("scope.sourceIssuerToken", "scope.institutionToken", "scope.sourceToken",
                            "scope.sourceEpochToken", "scope.identityVersion", "edgeType", "occurrence.token",
                            "occurrence.eventTime", "_id")));

    private GraphProjectionSchema() { }

    static void bootstrapEmptyDatabase(MongoDatabase database) {
        Objects.requireNonNull(database, "GRAPH_DATABASE_REQUIRED");
        requirePrincipal(database, SCHEMA_OWNER);
        if (!database.listCollectionNames().into(new ArrayList<>()).isEmpty()) { throw incompatible(); }
        database.createCollection(COLLECTION, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                .validator(validator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
        for (IndexSpec spec : INDEXES) {
            database.getCollection(COLLECTION).createIndex(spec.keys(), new IndexOptions().name(spec.name()).unique(spec.unique()));
        }
        verify(database, SCHEMA_OWNER);
    }

    static void verify(MongoDatabase database, String expectedPrincipal) {
        Objects.requireNonNull(database, "GRAPH_DATABASE_REQUIRED");
        if (!Set.of(SCHEMA_OWNER, WRITER, READER).contains(expectedPrincipal)) { throw incompatible(); }
        requirePrincipal(database, expectedPrincipal);
        List<String> names = database.listCollectionNames().into(new ArrayList<>());
        if (!names.equals(List.of(COLLECTION))) { throw incompatible(); }
        Document metadata = database.listCollections().filter(new Document("name", COLLECTION)).first();
        Document options = metadata == null ? null : metadata.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !validator().equals(options.get("validator"))
                || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) {
            throw incompatible();
        }
        if (database.getCollection(COLLECTION).find(new Document("$nor", List.of(validator()))).limit(1).first() != null) {
            throw incompatible();
        }
        verifyIndexes(database);
    }

    static Document validator() {
        Document lifecycle = object(
                List.of("state", "admittedAt", "validUntil"),
                new Document("state", new Document("bsonType", "string").append("enum", List.of("ACTIVE", "CONFLICT", "REVOKED")))
                        .append("admittedAt", date()).append("validUntil", date()));
        Document occurrence = object(List.of("token", "eventTime"),
                new Document("token", token()).append("eventTime", date()));
        Document device = object(List.of("token", "keyVersion"),
                new Document("token", token()).append("keyVersion", version()));
        Document score = object(List.of("eventToken", "createdAt", "fingerprint", "runId"),
                new Document("eventToken", token()).append("createdAt", new Document("bsonType", "string")
                        .append("minLength", 20).append("maxLength", 30)
                        .append("pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{3}|[.][0-9]{6}|[.][0-9]{9})?Z$"))
                .append("fingerprint", token())
                        .append("runId", new Document("bsonType", "string").append("enum", List.of(GraphScoredOccurrenceRef.INITIAL_RUN))));
        Document evidence = object(List.of("referenceId", "sourceReceiptReference", "rawCommitment", "enrichedCommitment",
                        "ownerAssertionReference", "coverageReference", "policyReference", "score", "stageASnapshotCommitment",
                        "coverageFrom", "asOf", "coverageGeneration", "coverageWatermark", "expiresAt"),
                new Document("referenceId", token()).append("sourceReceiptReference", token()).append("rawCommitment", token())
                        .append("enrichedCommitment", token()).append("ownerAssertionReference", token())
                        .append("coverageReference", token()).append("policyReference", token()).append("score", score)
                        .append("stageASnapshotCommitment", token()).append("coverageFrom", date()).append("asOf", date())
                        .append("coverageGeneration", number("long", 1)).append("coverageWatermark", date()).append("expiresAt", date()));
        Document scope = object(List.of("sourceIssuerToken", "institutionToken", "sourceToken", "sourceEpochToken", "identityVersion"),
                new Document("sourceIssuerToken", token()).append("institutionToken", token()).append("sourceToken", token())
                        .append("sourceEpochToken", token()).append("identityVersion", version()));
        Document projection = object(List.of("schemaVersion", "canonicalKeyVersion"),
                new Document("schemaVersion", new Document("bsonType", "int").append("enum", List.of(1)))
                        .append("canonicalKeyVersion", new Document("bsonType", "int").append("enum", List.of(1))));
        Document properties = new Document("_id", token())
                .append("schemaVersion", new Document("bsonType", "int").append("enum", List.of(SCHEMA_VERSION)))
                .append("projectionVersion", projection)
                .append("scope", scope)
                .append("edgeType", new Document("bsonType", "string").append("enum", List.of(GraphEdgeType.OCCURRENCE_ASSOCIATED_WITH_DEVICE.name())))
                .append("occurrence", occurrence).append("device", device).append("evidence", evidence).append("lifecycle", lifecycle);
        return new Document("$jsonSchema", object(new ArrayList<>(properties.keySet()), properties));
    }

    private static void verifyIndexes(MongoDatabase database) {
        Set<String> allowedIndexOptions = Set.of("v", "key", "name", "unique", "collation");
        List<Document> actual;
        try (var cursor = database.getCollection(COLLECTION).listIndexes().batchSize(INDEXES.size() + 2).iterator()) {
            actual = new ArrayList<>();
            while (cursor.hasNext()) {
                if (actual.size() >= INDEXES.size() + 1) { throw incompatible(); }
                actual.add(cursor.next());
            }
        }
        Set<String> expectedNames = new HashSet<>(Set.of("_id_"));
        INDEXES.forEach(spec -> expectedNames.add(spec.name()));
        Set<String> actualNames = new HashSet<>();
        for (Document index : actual) {
            if (index.getString("name") == null || !actualNames.add(index.getString("name"))) { throw incompatible(); }
        }
        if (!expectedNames.equals(actualNames)) { throw incompatible(); }
        for (IndexSpec spec : INDEXES) {
            Document found = actual.stream().filter(index -> spec.name().equals(index.getString("name"))).findFirst().orElseThrow(GraphProjectionSchema::incompatible);
            Document collation = found.get("collation", Document.class);
            Document actualKeys = found.get("key", Document.class);
            if (!allowedIndexOptions.containsAll(found.keySet()) || actualKeys == null
                    || !new ArrayList<>(spec.keys().entrySet()).equals(new ArrayList<>(actualKeys.entrySet()))
                    || spec.unique() != Boolean.TRUE.equals(found.get("unique"))
                    || Boolean.TRUE.equals(found.get("sparse")) || Boolean.TRUE.equals(found.get("hidden"))
                    || found.containsKey("expireAfterSeconds") || found.containsKey("partialFilterExpression")
                    || collation != null && !"simple".equals(collation.getString("locale"))) {
                throw incompatible();
            }
        }
    }

    private static void requirePrincipal(MongoDatabase database, String expectedPrincipal) {
        Document status = database.runCommand(new Document("connectionStatus", 1));
        Document authInfo = status.get("authInfo", Document.class);
        List<Document> users = authInfo == null ? null : authInfo.getList("authenticatedUsers", Document.class);
        if (users == null || users.size() != 1 || !expectedPrincipal.equals(users.getFirst().getString("user"))
                || !database.getName().equals(users.getFirst().getString("db"))) {
            throw incompatible();
        }
    }

    private static Document object(List<String> required, Document properties) {
        return new Document("bsonType", "object").append("required", required)
                .append("additionalProperties", false).append("properties", properties);
    }

    private static Document token() {
        return new Document("bsonType", "string").append("minLength", 64).append("maxLength", 64)
                .append("pattern", "^[0-9a-f]{64}$");
    }

    private static Document version() {
        return new Document("bsonType", "int").append("minimum", 1).append("maximum", 65_535);
    }

    private static Document date() { return new Document("bsonType", "date"); }
    private static Document number(String bsonType, long minimum) {
        return new Document("bsonType", bsonType).append("minimum", minimum);
    }

    private static Document keys(String... fields) {
        Document result = new Document();
        for (String field : fields) { result.append(field, 1); }
        return result;
    }

    private static IllegalStateException incompatible() {
        return new IllegalStateException("GRAPH_STORE_SCHEMA_INCOMPATIBLE");
    }

    private record IndexSpec(String name, boolean unique, Document keys) { }
}
