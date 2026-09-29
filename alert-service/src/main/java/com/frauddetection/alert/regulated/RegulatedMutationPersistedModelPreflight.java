package com.frauddetection.alert.regulated;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RegulatedMutationPersistedModelPreflight {

    static final String COLLECTION = "regulated_mutation_commands";
    private static final String MODEL_FIELD = "mutation_model_version";
    private static final String REVISION_FIELD = "revision";
    private static final String EXECUTION_STATUS_FIELD = "execution_status";
    private static final List<String> TERMINAL_EXECUTION_STATUSES = List.of("COMPLETED", "FAILED");

    private final MongoTemplate mongoTemplate;

    public RegulatedMutationPersistedModelPreflight(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Report inspect(int sampleLimit) {
        int boundedSampleLimit = Math.max(0, Math.min(sampleLimit, 100));
        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        Bson unsupportedModel = Filters.or(
                Filters.exists(MODEL_FIELD, false),
                Filters.eq(MODEL_FIELD, null),
                Filters.ne(MODEL_FIELD, RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name())
        );
        Bson invalidRevision = Filters.or(
                Filters.exists(REVISION_FIELD, false),
                Filters.eq(REVISION_FIELD, null),
                Filters.not(Filters.type(REVISION_FIELD, BsonType.INT64)),
                Filters.lt(REVISION_FIELD, 0L),
                Filters.eq(REVISION_FIELD, Long.MAX_VALUE)
        );
        Bson unsupportedPersistedContract = Filters.or(unsupportedModel, invalidRevision);
        Bson terminal = Filters.in(EXECUTION_STATUS_FIELD, TERMINAL_EXECUTION_STATUSES);
        Bson unfinished = Filters.or(
                Filters.exists(EXECUTION_STATUS_FIELD, false),
                Filters.nin(EXECUTION_STATUS_FIELD, TERMINAL_EXECUTION_STATUSES)
        );

        long unsupportedUnfinished = collection.countDocuments(Filters.and(unsupportedPersistedContract, unfinished));
        long unsupportedTerminal = collection.countDocuments(Filters.and(unsupportedPersistedContract, terminal));
        List<UnsupportedCommand> samples = new ArrayList<>();
        if (boundedSampleLimit > 0 && unsupportedUnfinished + unsupportedTerminal > 0) {
            Bson sampleFilter = unsupportedUnfinished > 0
                    ? Filters.and(unsupportedPersistedContract, unfinished)
                    : Filters.and(unsupportedPersistedContract, terminal);
            collection.find(sampleFilter)
                    .projection(Projections.include(
                            "_id",
                            MODEL_FIELD,
                            REVISION_FIELD,
                            EXECUTION_STATUS_FIELD,
                            "action",
                            "resource_type"
                    ))
                    .limit(boundedSampleLimit)
                    .forEach(document -> samples.add(sample(document)));
        }
        return new Report(unsupportedUnfinished, unsupportedTerminal, List.copyOf(samples));
    }

    private UnsupportedCommand sample(Document document) {
        Object rawModel = document.get(MODEL_FIELD);
        return new UnsupportedCommand(
                RegulatedMutationIntentHasher.hash(String.valueOf(document.get("_id"))),
                modelCategory(document, rawModel),
                stringValue(document.get("action")),
                stringValue(document.get("resource_type")),
                stringValue(document.get(EXECUTION_STATUS_FIELD))
        );
    }

    private String modelCategory(Document document, Object rawModel) {
        if (!document.containsKey(MODEL_FIELD)) {
            return "MISSING";
        }
        if (rawModel == null) {
            return "NULL";
        }
        if (!(rawModel instanceof String value)) {
            return "NON_STRING";
        }
        if ("LEGACY_REGULATED_MUTATION".equals(value)) {
            return "LEGACY";
        }
        if (!RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1.name().equals(value)) {
            return "UNKNOWN";
        }
        if (!document.containsKey(REVISION_FIELD)) {
            return "MISSING_REVISION";
        }
        Object rawRevision = document.get(REVISION_FIELD);
        if (rawRevision == null) {
            return "NULL_REVISION";
        }
        if (!(rawRevision instanceof Long revision) || revision < 0 || revision == Long.MAX_VALUE) {
            return "INVALID_REVISION";
        }
        return "UNKNOWN";
    }

    private String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    public record Report(
            long unsupportedUnfinishedCount,
            long unsupportedTerminalCount,
            List<UnsupportedCommand> samples
    ) {
        public boolean blocksStartup() {
            return unsupportedUnfinishedCount > 0;
        }
    }

    public record UnsupportedCommand(
            String commandIdHash,
            String modelCategory,
            String action,
            String resourceType,
            String executionStatus
    ) {
    }
}
