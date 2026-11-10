package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface SourceLineageAccess {
    SourceLineage raw(TransactionRawEvent event);
    SourceLineage enriched(TransactionEnrichedEvent event);
    SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output);

    /** Binds and durably stores the exact event before its Kafka publication. */
    default SourceEnrichedPublication prepareEnrichedPublication(TransactionRawEvent input, TransactionEnrichedEvent output) {
        return SourceEnrichedPublication.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    /** Records a confirmed Kafka acknowledgement for an exact previously prepared event. */
    default SourceLineage markEnrichedPublished(TransactionRawEvent input, TransactionEnrichedEvent output) {
        return SourceLineage.unavailable(SourceLineage.State.UNAVAILABLE);
    }

    default boolean isLegacyOnly() { return false; }

    /** Explicit deployment mode used only when attested ingress is not configured. */
    static SourceLineageAccess legacyOnly() {
        return new SourceLineageAccess() {
            @Override public SourceLineage raw(TransactionRawEvent event) { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) {
                return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED);
            }
            @Override public boolean isLegacyOnly() { return true; }
        };
    }

    static SourceLineageAccess disabled() {
        return new SourceLineageAccess() {
            private SourceLineage absent() { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage raw(TransactionRawEvent event) { return absent(); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return absent(); }
            @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) { return absent(); }
        };
    }
}
