package com.frauddetection.alert.trust;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationIntentHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class TrustIncidentMaterializer {

    private static final Logger log = LoggerFactory.getLogger(TrustIncidentMaterializer.class);
    private static final int MAX_SIGNALS_PER_BATCH = 100;

    private final TrustIncidentRepository repository;
    private final MongoTemplate mongoTemplate;
    private final TrustIncidentPolicy policy;
    private final AlertServiceMetrics metrics;

    public TrustIncidentMaterializer(
            TrustIncidentRepository repository,
            MongoTemplate mongoTemplate,
            TrustIncidentPolicy policy,
            AlertServiceMetrics metrics
    ) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.policy = policy;
        this.metrics = metrics;
    }

    public TrustIncidentMaterializationResponse materialize(List<TrustSignal> signals) {
        List<TrustSignal> bounded = signals == null ? List.of() : signals.stream()
                .limit(MAX_SIGNALS_PER_BATCH)
                .toList();
        java.util.ArrayList<TrustIncidentResponse> incidents = new java.util.ArrayList<>();
        for (TrustSignal signal : bounded) {
            try {
                incidents.add(TrustIncidentResponse.from(materializeSignal(signal)));
            } catch (RuntimeException exception) {
                int failed = bounded.size() - incidents.size();
                TrustIncidentMaterializationResponse response = new TrustIncidentMaterializationResponse(
                        incidents.isEmpty() ? "FAILED" : "PARTIAL",
                        bounded.size(),
                        incidents.size(),
                        bounded.size(),
                        incidents.size(),
                        failed,
                        !incidents.isEmpty(),
                        "PERSISTENCE_UNAVAILABLE",
                        List.copyOf(incidents)
                );
                throw new TrustIncidentMaterializationException(response, exception);
            }
        }
        return new TrustIncidentMaterializationResponse(
                "AVAILABLE",
                bounded.size(),
                incidents.size(),
                bounded.size(),
                incidents.size(),
                0,
                false,
                null,
                List.copyOf(incidents)
        );
    }

    TrustIncidentDocument materializeSignal(TrustSignal signal) {
        String fingerprint = fingerprint(signal);
        String activeDedupeKey = activeDedupeKey(signal.type(), signal.source(), fingerprint);
        Instant now = Instant.now();
        List<String> evidenceRefs = policy.boundedEvidenceRefs(signal.evidenceRefs());
        Update update = new Update()
                .setOnInsert("_id", UUID.randomUUID().toString())
                .setOnInsert("type", signal.type())
                .setOnInsert("severity", severity(signal).name())
                .setOnInsert("source", signal.source())
                .setOnInsert("fingerprint", fingerprint)
                .setOnInsert("active_dedupe_key", activeDedupeKey)
                .setOnInsert("status", TrustIncidentStatus.OPEN.name())
                .setOnInsert("first_seen_at", now)
                .setOnInsert("created_at", now)
                .set("last_seen_at", now)
                .set("updated_at", now)
                .inc("occurrence_count", 1);
        if (!evidenceRefs.isEmpty()) {
            update.addToSet("evidence_refs").each(evidenceRefs.toArray());
        }
        try {
            TrustIncidentDocument incident = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("active_dedupe_key").is(activeDedupeKey)),
                    update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    TrustIncidentDocument.class
            );
            if (incident == null) {
                throw new IllegalStateException("trust incident materialization returned no document");
            }
            if (incident.getOccurrenceCount() <= 1L) {
                metrics.recordTrustIncidentMaterialized(signal.type(), severity(signal).name(), "CREATED");
            } else {
                metrics.recordTrustIncidentDeduped(signal.type(), severity(signal).name());
            }
            return incident;
        } catch (DuplicateKeyException duplicate) {
            metrics.recordTrustIncidentDeduped(signal.type(), severity(signal).name());
            return repository.findByActiveDedupeKey(activeDedupeKey).orElseThrow(() -> duplicate);
        } catch (RuntimeException exception) {
            metrics.recordTrustIncidentMaterializationFailed("PERSISTENCE_UNAVAILABLE");
            log.warn("Trust incident materialization failed: reason=PERSISTENCE_UNAVAILABLE");
            throw exception;
        }
    }

    private TrustIncidentSeverity severity(TrustSignal signal) {
        return signal.severity() == null ? policy.severity(signal.type()) : signal.severity();
    }

    private String fingerprint(TrustSignal signal) {
        return signal.fingerprint() == null || signal.fingerprint().isBlank()
                ? RegulatedMutationIntentHasher.hash(signal.type() + "|" + signal.source())
                : RegulatedMutationIntentHasher.hash(signal.fingerprint());
    }

    private String activeDedupeKey(String type, String source, String fingerprint) {
        return type + ":" + source + ":" + fingerprint;
    }
}
