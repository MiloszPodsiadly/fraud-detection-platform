package com.frauddetection.alert.regulated.chaos;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationCheckpointRenewalDecision;
import com.frauddetection.alert.regulated.RegulatedMutationCheckpointRenewalService;
import com.frauddetection.alert.regulated.RegulatedMutationClaimToken;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationLeaseRenewalService;
import com.frauddetection.alert.regulated.RegulatedMutationSafeCheckpointPolicy;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.time.Instant;

@Configuration
@Profile("fdp38-live-runtime-checkpoint")
class Fdp38LiveRuntimeCheckpointBarrierConfiguration {

    @Bean
    @Primary
    RegulatedMutationCheckpointRenewalService fdp38LiveRuntimeCheckpointBarrier(
            RegulatedMutationSafeCheckpointPolicy checkpointPolicy,
            RegulatedMutationLeaseRenewalService leaseRenewalService,
            AlertServiceMetrics metrics,
            @Value("${app.regulated-mutations.checkpoint-renewal.extension:PT30S}") Duration requestedExtension,
            @Value("${spring.data.mongodb.uri}") String mongoUri,
            @Value("${app.fdp38.live-runtime-checkpoint.name}") Fdp38LiveRuntimeCheckpoint checkpoint,
            @Value("${app.fdp38.live-runtime-checkpoint.idempotency-key}") String idempotencyKey
    ) {
        return new BlockingCheckpointRenewalService(
                checkpointPolicy,
                leaseRenewalService,
                metrics,
                requestedExtension,
                mongoUri,
                checkpoint,
                idempotencyKey
        );
    }

    private static final class BlockingCheckpointRenewalService extends RegulatedMutationCheckpointRenewalService
            implements AutoCloseable {

        private final MongoClient mongoClient;
        private final String databaseName;
        private final Fdp38LiveRuntimeCheckpoint checkpoint;
        private final String idempotencyKey;

        private BlockingCheckpointRenewalService(
                RegulatedMutationSafeCheckpointPolicy checkpointPolicy,
                RegulatedMutationLeaseRenewalService leaseRenewalService,
                AlertServiceMetrics metrics,
                Duration requestedExtension,
                String mongoUri,
                Fdp38LiveRuntimeCheckpoint checkpoint,
                String idempotencyKey
        ) {
            super(checkpointPolicy, leaseRenewalService, metrics, requestedExtension);
            ConnectionString connectionString = new ConnectionString(mongoUri);
            this.mongoClient = MongoClients.create(connectionString);
            this.databaseName = connectionString.getDatabase();
            this.checkpoint = checkpoint;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public RegulatedMutationCheckpointRenewalDecision beforeLegacyBusinessCommit(
                RegulatedMutationClaimToken claimToken,
                RegulatedMutationCommandDocument document
        ) {
            RegulatedMutationCheckpointRenewalDecision decision = super.beforeLegacyBusinessCommit(claimToken, document);
            blockIfTarget(document, Fdp38LiveRuntimeCheckpoint.BEFORE_LEGACY_BUSINESS_MUTATION);
            blockIfTarget(document, Fdp38LiveRuntimeCheckpoint.AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION);
            return decision;
        }

        @Override
        public RegulatedMutationCheckpointRenewalDecision beforeEvidenceGatedFinalize(
                RegulatedMutationClaimToken claimToken,
                RegulatedMutationCommandDocument document
        ) {
            RegulatedMutationCheckpointRenewalDecision decision = super.beforeEvidenceGatedFinalize(claimToken, document);
            blockIfTarget(document, Fdp38LiveRuntimeCheckpoint.BEFORE_FDP29_LOCAL_FINALIZE);
            return decision;
        }

        @Override
        public RegulatedMutationCheckpointRenewalDecision beforeSuccessAuditRetry(
                RegulatedMutationClaimToken claimToken,
                RegulatedMutationCommandDocument document
        ) {
            RegulatedMutationCheckpointRenewalDecision decision = super.beforeSuccessAuditRetry(claimToken, document);
            blockIfTarget(document, Fdp38LiveRuntimeCheckpoint.BEFORE_SUCCESS_AUDIT_RETRY);
            return decision;
        }

        private void blockIfTarget(
                RegulatedMutationCommandDocument document,
                Fdp38LiveRuntimeCheckpoint candidate
        ) {
            if (checkpoint != candidate || document == null || !idempotencyKey.equals(document.getIdempotencyKey())) {
                return;
            }
            recordBarrier(document);
            waitUntilKilledOrReleased();
        }

        private void recordBarrier(RegulatedMutationCommandDocument document) {
            mongoClient.getDatabase(databaseName)
                    .getCollection("fdp38_live_checkpoint_barriers")
                    .replaceOne(
                            new Document("_id", idempotencyKey),
                            new Document("_id", idempotencyKey)
                                    .append("checkpoint", checkpoint.name())
                                    .append("checkpoint_reached", true)
                                    .append("state", "BLOCKED")
                                    .append("idempotency_key_hash_only_note", "present")
                                    .append("mutation_command_id", document.getId())
                                    .append("command_state", document.getState() == null ? null : document.getState().name())
                                    .append("execution_status", document.getExecutionStatus() == null
                                            ? null
                                            : document.getExecutionStatus().name())
                                    .append("reached_at", Instant.now()),
                            new com.mongodb.client.model.ReplaceOptions().upsert(true)
                    );
        }

        private void waitUntilKilledOrReleased() {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                Document barrier = mongoClient.getDatabase(databaseName)
                        .getCollection("fdp38_live_checkpoint_barriers")
                        .find(new Document("_id", idempotencyKey))
                        .first();
                if (barrier != null && Boolean.TRUE.equals(barrier.getBoolean("release"))) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted at FDP-38 live runtime checkpoint barrier.", exception);
                }
            }
            throw new IllegalStateException("FDP-38 live runtime checkpoint barrier timed out before kill or release.");
        }

        @Override
        public void close() {
            mongoClient.close();
        }
    }
}
