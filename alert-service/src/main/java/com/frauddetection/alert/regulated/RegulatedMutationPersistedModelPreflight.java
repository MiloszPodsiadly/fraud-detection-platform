package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RegulatedMutationPersistedModelPreflight {

    static final String COLLECTION = "regulated_mutation_commands";
    private static final String MODEL_FIELD = "mutation_model_version";
    private static final String REVISION_FIELD = "revision";
    private static final String STATE_FIELD = "state";
    private static final String EXECUTION_STATUS_FIELD = "execution_status";
    private static final String ACTION_FIELD = "action";
    private static final String RESOURCE_TYPE_FIELD = "resource_type";
    private static final List<String> TERMINAL_EXECUTION_STATUSES = List.of("COMPLETED", "FAILED");
    private static final Set<String> CURRENT_STATES = enumNames(RegulatedMutationState.values());
    private static final Set<String> CURRENT_EXECUTION_STATUSES = enumNames(RegulatedMutationExecutionStatus.values());
    private static final Set<String> CURRENT_ACTIONS = enumNames(AuditAction.values());
    private static final Set<String> CURRENT_RESOURCE_TYPES = enumNames(AuditResourceType.values());

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
        Bson invalidState = invalidEnumField(STATE_FIELD, CURRENT_STATES);
        Bson invalidExecutionStatus = invalidEnumField(EXECUTION_STATUS_FIELD, CURRENT_EXECUTION_STATUSES);
        Bson invalidAction = invalidEnumField(ACTION_FIELD, CURRENT_ACTIONS);
        Bson invalidResourceType = invalidEnumField(RESOURCE_TYPE_FIELD, CURRENT_RESOURCE_TYPES);
        Bson unsupportedActionResourcePair = Filters.nor(RegulatedMutationDefinitions.all().stream()
                .map(definition -> Filters.and(
                        Filters.eq(ACTION_FIELD, definition.action().name()),
                        Filters.eq(RESOURCE_TYPE_FIELD, definition.resourceType().name())
                ))
                .toList());
        Bson unsupportedPersistedContract = Filters.or(
                unsupportedModel,
                invalidRevision,
                invalidState,
                invalidExecutionStatus,
                invalidAction,
                invalidResourceType,
                unsupportedActionResourcePair
        );
        Bson terminal = Filters.in(EXECUTION_STATUS_FIELD, TERMINAL_EXECUTION_STATUSES);
        Bson unfinished = Filters.or(
                Filters.exists(EXECUTION_STATUS_FIELD, false),
                Filters.nin(EXECUTION_STATUS_FIELD, TERMINAL_EXECUTION_STATUSES)
        );

        long unsupportedUnfinished = collection.countDocuments(Filters.and(unsupportedPersistedContract, unfinished));
        long unsupportedTerminal = collection.countDocuments(Filters.and(unsupportedPersistedContract, terminal));
        List<UnsupportedCommand> samples = new ArrayList<>();
        if (boundedSampleLimit > 0 && unsupportedUnfinished + unsupportedTerminal > 0) {
            collection.find(unsupportedPersistedContract)
                    .projection(Projections.include(
                            "_id",
                            MODEL_FIELD,
                            REVISION_FIELD,
                            STATE_FIELD,
                            EXECUTION_STATUS_FIELD,
                            ACTION_FIELD,
                            RESOURCE_TYPE_FIELD
                    ))
                    .limit(boundedSampleLimit)
                    .forEach(document -> samples.add(sample(document)));
        }
        return new Report(unsupportedUnfinished, unsupportedTerminal, List.copyOf(samples));
    }

    private UnsupportedCommand sample(Document document) {
        return new UnsupportedCommand(
                RegulatedMutationIntentHasher.hash(String.valueOf(document.get("_id"))),
                contractCategory(document),
                safeEnumValue(document, ACTION_FIELD, CURRENT_ACTIONS),
                safeEnumValue(document, RESOURCE_TYPE_FIELD, CURRENT_RESOURCE_TYPES),
                safeEnumValue(document, EXECUTION_STATUS_FIELD, CURRENT_EXECUTION_STATUSES)
        );
    }

    String contractCategory(Document document) {
        Object rawModel = document.get(MODEL_FIELD);
        if (!document.containsKey(MODEL_FIELD)) {
            return "MISSING";
        }
        if (rawModel == null) {
            return "NULL";
        }
        if (!(rawModel instanceof String value)) {
            return "NON_STRING";
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
        String state = safeEnumValue(document, STATE_FIELD, CURRENT_STATES);
        if (!CURRENT_STATES.contains(state)) {
            return state + "_STATE";
        }
        String executionStatus = safeEnumValue(document, EXECUTION_STATUS_FIELD, CURRENT_EXECUTION_STATUSES);
        if (!CURRENT_EXECUTION_STATUSES.contains(executionStatus)) {
            return executionStatus + "_EXECUTION_STATUS";
        }
        String action = safeEnumValue(document, ACTION_FIELD, CURRENT_ACTIONS);
        if (!CURRENT_ACTIONS.contains(action)) {
            return action + "_ACTION";
        }
        String resourceType = safeEnumValue(document, RESOURCE_TYPE_FIELD, CURRENT_RESOURCE_TYPES);
        if (!CURRENT_RESOURCE_TYPES.contains(resourceType)) {
            return resourceType + "_RESOURCE_TYPE";
        }
        boolean supportedPair = RegulatedMutationDefinitions.find(
                AuditAction.valueOf(action),
                AuditResourceType.valueOf(resourceType)
        ).isPresent();
        return supportedPair ? "SUPPORTED" : "UNSUPPORTED_ACTION_RESOURCE_PAIR";
    }

    private String safeEnumValue(Document document, String field, Set<String> allowedValues) {
        if (!document.containsKey(field)) {
            return "MISSING";
        }
        Object value = document.get(field);
        if (value == null) {
            return "NULL";
        }
        if (!(value instanceof String string)) {
            return "NON_STRING";
        }
        return allowedValues.contains(string) ? string : "UNKNOWN";
    }

    private static Bson invalidEnumField(String field, Set<String> allowedValues) {
        return Filters.or(
                Filters.exists(field, false),
                Filters.eq(field, null),
                Filters.not(Filters.type(field, BsonType.STRING)),
                Filters.nin(field, allowedValues)
        );
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Report(
            long unsupportedUnfinishedCount,
            long unsupportedTerminalCount,
            List<UnsupportedCommand> samples
    ) {
        public boolean blocksStartup() {
            return unsupportedUnfinishedCount + unsupportedTerminalCount > 0;
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
