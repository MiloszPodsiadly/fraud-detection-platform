package com.frauddetection.alert.regulated.chaos;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.service.DecisionOutboxWriter;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.common.events.enums.AlertStatus;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

@Configuration
@Profile("fdp36-live-in-flight")
class Fdp36LiveInFlightMutationBlockerConfiguration {

    @Bean
    @Primary
    SubmitDecisionMutationHandler fdp36BlockingSubmitDecisionMutationHandler(
            AlertRepository alertRepository,
            AlertDocumentMapper alertDocumentMapper,
            DecisionOutboxWriter decisionOutboxWriter,
            @Value("${spring.data.mongodb.uri}") String mongoUri,
            @Value("${app.fdp36.live-in-flight.idempotency-key}") String blockedIdempotencyKey
    ) {
        return new BlockingSubmitDecisionMutationHandler(
                alertRepository,
                alertDocumentMapper,
                decisionOutboxWriter,
                mongoUri,
                blockedIdempotencyKey
        );
    }

    private static final class BlockingSubmitDecisionMutationHandler extends SubmitDecisionMutationHandler implements AutoCloseable {

        private final MongoClient mongoClient;
        private final String databaseName;
        private final String blockedIdempotencyKey;

        private BlockingSubmitDecisionMutationHandler(
                AlertRepository alertRepository,
                AlertDocumentMapper alertDocumentMapper,
                DecisionOutboxWriter decisionOutboxWriter,
                String mongoUri,
                String blockedIdempotencyKey
        ) {
            super(alertRepository, alertDocumentMapper, decisionOutboxWriter);
            ConnectionString connectionString = new ConnectionString(mongoUri);
            this.mongoClient = MongoClients.create(connectionString);
            this.databaseName = connectionString.getDatabase();
            this.blockedIdempotencyKey = blockedIdempotencyKey;
        }

        @Override
        public AlertDocument applyDecision(
                String alertId,
                SubmitAnalystDecisionRequest request,
                AlertStatus resultingStatus,
                String actorId,
                String idempotencyKey,
                String requestHash,
                String mutationCommandId,
                SubmitDecisionOperationStatus operationStatus
        ) {
            if (blockedIdempotencyKey.equals(idempotencyKey)) {
                recordBarrier(alertId, mutationCommandId, actorId);
                waitUntilKilledOrReleased();
            }
            return super.applyDecision(
                    alertId,
                    request,
                    resultingStatus,
                    actorId,
                    idempotencyKey,
                    requestHash,
                    mutationCommandId,
                    operationStatus
            );
        }

        private void recordBarrier(String alertId, String mutationCommandId, String actorId) {
            mongoClient.getDatabase(databaseName)
                    .getCollection("fdp36_live_inflight_barriers")
                    .replaceOne(
                            new Document("_id", blockedIdempotencyKey),
                            new Document("_id", blockedIdempotencyKey)
                                    .append("state", "BLOCKED_BEFORE_BUSINESS_MUTATION")
                                    .append("alert_id", alertId)
                                    .append("mutation_command_id", mutationCommandId)
                                    .append("actor_id_hash_only_note", actorId == null ? "absent" : "present")
                                    .append("reached_at", Instant.now()),
                            new com.mongodb.client.model.ReplaceOptions().upsert(true)
                    );
        }

        private void waitUntilKilledOrReleased() {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                Document barrier = mongoClient.getDatabase(databaseName)
                        .getCollection("fdp36_live_inflight_barriers")
                        .find(new Document("_id", blockedIdempotencyKey))
                        .first();
                if (barrier != null && Boolean.TRUE.equals(barrier.getBoolean("release"))) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting at FDP-36 live in-flight checkpoint", exception);
                }
            }
            throw new IllegalStateException("FDP-36 live in-flight checkpoint was not killed or released in time.");
        }

        @Override
        public void close() {
            mongoClient.close();
        }
    }
}
