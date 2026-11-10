package com.frauddetection.enricher.mapper;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.source.SourceEventCommitment;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class TransactionEnrichedEventMapper {

    public TransactionEnrichedEvent toEvent(TransactionRawEvent event, EnrichedTransactionFeatures features) {
        return toEvent(event, features, UUID.randomUUID().toString(), Instant.now());
    }

    /** Stable occurrence identity lets Mongo reject changed replays and makes identical retries addressable. */
    public TransactionEnrichedEvent toAttestedEvent(TransactionRawEvent event, EnrichedTransactionFeatures features) {
        if (event == null || event.createdAt() == null) { throw new IllegalArgumentException("RAW_EVENT_CREATED_AT_REQUIRED"); }
        String commitment = SourceEventCommitment.raw(event);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("enriched-event-occurrence/v1:".getBytes(StandardCharsets.US_ASCII));
            digest.update(commitment.getBytes(StandardCharsets.US_ASCII));
            return toEvent(event, features, "enr-" + HexFormat.of().formatHex(digest.digest()), event.createdAt());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("ENRICHED_EVENT_ID_UNAVAILABLE");
        }
    }

    private TransactionEnrichedEvent toEvent(TransactionRawEvent event, EnrichedTransactionFeatures features,
                                              String eventId, Instant createdAt) {
        return new TransactionEnrichedEvent(
                eventId,
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                createdAt,
                event.transactionTimestamp(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                features.featureSnapshot()
        );
    }
}
