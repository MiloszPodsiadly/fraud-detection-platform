package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import org.bson.Document;

import java.util.List;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

final class SourceLineageSchema {
    static final String RAW = "source_lineage_raw";
    static final String ENRICHED = "source_lineage_enriched";
    static final String RAW_COUNTS = "source_lineage_raw_counts";
    static final String ENRICHED_COUNTS = "source_lineage_enriched_counts";
    static final String ENRICHED_PUBLICATIONS = SourceEnrichedPublicationCipher.COLLECTION;
    static final List<String> COLLECTIONS = List.of(RAW, ENRICHED, RAW_COUNTS, ENRICHED_COUNTS);
    static final int MAX_LINKS_PER_SOURCE = 32;
    static final String SOURCE_LINK_INDEX = "lineage_source_links";

    static void initialize(MongoDatabase database, boolean owner) {
        long present = database.listCollectionNames().into(new java.util.ArrayList<>()).stream()
                .filter(COLLECTIONS::contains).count();
        if (present != COLLECTIONS.size() && (present != 0 || !owner)) { throw invalid(); }
        for (String name : COLLECTIONS) {
            Document existing = database.listCollections().filter(eq("name", name)).first();
            if (existing == null) {
                if (!owner) { throw invalid(); }
                database.createCollection(name, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                        .validator(validator(name)).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
                if (!counts(name) && !publication(name)) {
                    database.getCollection(name).createIndex(new Document("eventCommitment", 1),
                            new IndexOptions().name("lineage_content_unique").unique(true));
                    database.getCollection(name).createIndex(new Document("receiptId", 1), new IndexOptions().name(SOURCE_LINK_INDEX));
                }
                existing = database.listCollections().filter(eq("name", name)).first();
            }
            Document options = existing == null ? null : existing.get("options", Document.class);
            if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                    || !validator(name).equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                    || !"error".equals(options.getString("validationAction"))) { throw invalid(); }
            var indexes = database.getCollection(name).listIndexes().into(new java.util.ArrayList<>());
            if (indexes.size() != (counts(name) || publication(name) ? 1 : 3)) { throw invalid(); }
            for (Document index : indexes) {
                if (index.containsKey("expireAfterSeconds") || index.containsKey("partialFilterExpression")
                        || index.containsKey("collation") || Boolean.TRUE.equals(index.get("sparse"))
                        || Boolean.TRUE.equals(index.get("hidden"))) { throw invalid(); }
                if ("_id_".equals(index.getString("name")) && new Document("_id", 1).equals(index.get("key"))) { continue; }
                if (!counts(name) && !publication(name) && "lineage_content_unique".equals(index.getString("name"))
                        && new Document("eventCommitment", 1).equals(index.get("key")) && Boolean.TRUE.equals(index.get("unique"))) { continue; }
                if (!counts(name) && !publication(name) && SOURCE_LINK_INDEX.equals(index.getString("name"))
                        && new Document("receiptId", 1).equals(index.get("key")) && !Boolean.TRUE.equals(index.get("unique"))) { continue; }
                throw invalid();
            }
        }
        if (owner) { initializeEnrichedPublications(database, true); }
    }

    static void initializeEnrichedPublications(MongoDatabase database, boolean owner) {
        String name = ENRICHED_PUBLICATIONS;
        Document existing = database.listCollections().filter(eq("name", name)).first();
        if (existing == null) {
            if (!owner) { throw invalid(); }
            database.createCollection(name, new CreateCollectionOptions().validationOptions(new ValidationOptions()
                    .validator(publicationValidator()).validationLevel(ValidationLevel.STRICT).validationAction(ValidationAction.ERROR)));
            existing = database.listCollections().filter(eq("name", name)).first();
        }
        Document options = existing == null ? null : existing.get("options", Document.class);
        if (options == null || !options.keySet().equals(Set.of("validator", "validationLevel", "validationAction"))
                || !publicationValidator().equals(options.get("validator")) || !"strict".equals(options.getString("validationLevel"))
                || !"error".equals(options.getString("validationAction"))) { throw invalid(); }
        var indexes = database.getCollection(name).listIndexes().into(new java.util.ArrayList<>());
        if (indexes.size() != 1 || !"_id_".equals(indexes.getFirst().getString("name"))
                || !new Document("_id", 1).equals(indexes.getFirst().get("key"))) { throw invalid(); }
    }
    private static Document validator(String name) {
        if (publication(name)) { return publicationValidator(); }
        Document properties = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)));
        if (counts(name)) {
            properties.append("count", type("int").append("minimum", 1).append("maximum", MAX_LINKS_PER_SOURCE));
        } else {
            properties.append("eventId", type("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
                    .append("eventCreatedAt", type("string").append("maxLength", 64)).append("eventCommitment", hash())
                    .append("receiptId", hash()).append("parentCommitment", hash()).append("expiresAt", type("date"))
                    .append("state", type("string").append("enum", List.of("BOUND", "CONFLICT")));
        }
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", List.copyOf(properties.keySet())).append("properties", properties));
    }
    private static Document publicationValidator() {
        Document properties = new Document("_id", hash()).append("schemaVersion", type("int").append("enum", List.of(1)))
                .append("receiptId", hash()).append("rawCommitment", hash()).append("enrichedCommitment", hash())
                .append("eventId", type("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
                .append("eventCreatedAt", type("string").append("maxLength", 64)).append("expiresAt", type("date"))
                .append("state", type("string").append("enum", List.of("PENDING", "PUBLISHED")))
                .append("publishedAt", type(List.of("date", "null"))).append("keyId", type("string")
                        .append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
                .append("nonce", type("binData")).append("ciphertext", type("binData"));
        return new Document("$jsonSchema", type("object").append("additionalProperties", false)
                .append("required", List.copyOf(properties.keySet())).append("properties", properties));
    }
    private static boolean counts(String name) { return name.equals(RAW_COUNTS) || name.equals(ENRICHED_COUNTS); }
    private static boolean publication(String name) { return name.equals(ENRICHED_PUBLICATIONS); }
    private static Document hash() { return type("string").append("pattern", "^[0-9a-f]{64}$"); }
    private static Document type(Object value) { return new Document("bsonType", value); }
    private static IllegalStateException invalid() { return new IllegalStateException("SOURCE_LINEAGE_SCHEMA_INCOMPATIBLE"); }
}
