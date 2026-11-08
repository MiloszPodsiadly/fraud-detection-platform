package com.frauddetection.common.events.source;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;

public interface SourceLineageAccess {
    SourceLineage raw(TransactionRawEvent event);
    SourceLineage enriched(TransactionEnrichedEvent event);
    SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output);

    static SourceLineageAccess disabled() {
        return new SourceLineageAccess() {
            private SourceLineage absent() { return SourceLineage.unavailable(SourceLineage.State.UNVERIFIED); }
            @Override public SourceLineage raw(TransactionRawEvent event) { return absent(); }
            @Override public SourceLineage enriched(TransactionEnrichedEvent event) { return absent(); }
            @Override public SourceLineage bindEnriched(TransactionRawEvent input, TransactionEnrichedEvent output) { return absent(); }
        };
    }
}
