package com.frauddetection.alert.outbox;

import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TransactionalOutboxPersistedContractPreflight {

    static final String COLLECTION = "transactional_outbox_records";
    private static final String RETIRED_REASON_FIELD = "resolution_reason";
    private static final String STATUS_FIELD = "status";
    private static final List<String> TERMINAL_STATUSES = List.of(
            TransactionalOutboxStatus.PUBLISHED.name(),
            TransactionalOutboxStatus.FAILED_TERMINAL.name(),
            TransactionalOutboxStatus.RECOVERY_REQUIRED.name()
    );

    private final MongoTemplate mongoTemplate;

    public TransactionalOutboxPersistedContractPreflight(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Report inspect(int sampleLimit) {
        int boundedSampleLimit = Math.max(0, Math.min(sampleLimit, 100));
        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        Bson retiredShape = Filters.exists(RETIRED_REASON_FIELD, true);
        Bson terminal = Filters.in(STATUS_FIELD, TERMINAL_STATUSES);
        Bson unfinished = Filters.or(
                Filters.exists(STATUS_FIELD, false),
                Filters.nin(STATUS_FIELD, TERMINAL_STATUSES)
        );

        long unsupportedUnfinished = collection.countDocuments(Filters.and(retiredShape, unfinished));
        long unsupportedTerminal = collection.countDocuments(Filters.and(retiredShape, terminal));
        List<UnsupportedOutboxRecord> samples = new ArrayList<>();
        if (boundedSampleLimit > 0 && unsupportedUnfinished + unsupportedTerminal > 0) {
            collection.find(retiredShape)
                    .projection(Projections.include(
                            "_id",
                            STATUS_FIELD,
                            "resolution_pending",
                            "resolution_request_reason",
                            "resolution_approval_reason"
                    ))
                    .limit(boundedSampleLimit)
                    .forEach(document -> samples.add(sample(document)));
        }
        return new Report(unsupportedUnfinished, unsupportedTerminal, List.copyOf(samples));
    }

    private UnsupportedOutboxRecord sample(Document document) {
        return new UnsupportedOutboxRecord(
                RegulatedMutationIntentHasher.hash(String.valueOf(document.get("_id"))),
                safeString(document.get(STATUS_FIELD)),
                resolutionPhase(document),
                document.containsKey("resolution_request_reason"),
                document.containsKey("resolution_approval_reason")
        );
    }

    private String resolutionPhase(Document document) {
        Object pending = document.get("resolution_pending");
        if (Boolean.TRUE.equals(pending)) {
            return "PENDING_REQUEST";
        }
        if (Boolean.FALSE.equals(pending)) {
            return "COMPLETED_OR_UNSET_APPROVAL";
        }
        return "UNKNOWN";
    }

    private String safeString(Object value) {
        return value instanceof String string ? string : "UNKNOWN";
    }

    public record Report(
            long unsupportedUnfinishedCount,
            long unsupportedTerminalCount,
            List<UnsupportedOutboxRecord> samples
    ) {
        public boolean blocksStartup() {
            return unsupportedUnfinishedCount + unsupportedTerminalCount > 0;
        }
    }

    public record UnsupportedOutboxRecord(
            String eventIdHash,
            String status,
            String resolutionPhase,
            boolean hasCanonicalRequestReason,
            boolean hasCanonicalApprovalReason
    ) {
    }
}
