package com.frauddetection.scoring.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.function.Supplier;

import static com.frauddetection.common.events.source.MongoSourceOccurrenceStore.Status.*;

/** Explicitly enabled private publication path. All retries replay the stored fact, never call ML or regenerate IDs. */
public final class SourceOccurrencePublisher {
    private static final Logger log = LoggerFactory.getLogger(SourceOccurrencePublisher.class);
    private final MongoSourceOccurrenceStore store;
    private final TransactionScoredEventPublisher kafka;
    public SourceOccurrencePublisher(MongoSourceOccurrenceStore store, TransactionScoredEventPublisher kafka) {
        this.store = Objects.requireNonNull(store); this.kafka = Objects.requireNonNull(kafka);
    }
    public TransactionScoredEvent publish(TransactionEnrichedEvent input, String runId, Supplier<TransactionScoredEvent> compute) {
        var existing = store.resume(input, runId);
        if (existing.status() == ABSENT) { existing = store.prepare(input, runId, compute.get()); }
        if (existing.status() == PUBLISHED) { return existing.event(); }
        if (existing.status() != PENDING) { throw unresolved(); }
        return deliver(existing.id());
    }
    private TransactionScoredEvent deliver(String id) {
        var claim = store.claim(id);
        if (claim.status() == PUBLISHED) { return claim.event(); }
        if (claim.status() != PENDING) { throw unresolved(); }
        try { kafka.publish(claim.event()); }
        catch (RuntimeException unknownAck) { throw unresolved(); }
        var confirmed = store.confirm(id, claim.event());
        if (confirmed.status() != PUBLISHED) { throw unresolved(); }
        return confirmed.event();
    }
    public int recoverPending(int limit) {
        int recovered = 0;
        int unresolved = 0;
        for (String id : store.pending(limit)) {
            try { deliver(id); recovered++; } catch (IllegalStateException unavailable) { unresolved++; }
        }
        if (unresolved > 0) { log.atWarn().addKeyValue("unresolvedCount", unresolved).log("Source scored recovery incomplete."); }
        return recovered;
    }
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void recover() {
        try { recoverPending(25); store.compactExpired(25); }
        catch (RuntimeException unavailable) { log.warn("Source scored recovery unavailable."); }
    }
    private static IllegalStateException unresolved() { return new IllegalStateException("SOURCE_SCORED_PUBLICATION_UNRESOLVED"); }
}
