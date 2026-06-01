package com.frauddetection.common.events.intelligence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIntelligenceConsumerReadinessDocsTest {

    @Test
    void docsStateConsumerFirstBoundaryAndFutureProducerGate() throws Exception {
        String docs = EngineIntelligenceFdp93SourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        );

        assertThat(docs).contains(
                "Consumer-first Engine Intelligence Rollout Readiness",
                "Do not emit what consumers have not proven they can safely tolerate",
                "FDP-93 proves consumers can safely tolerate engineIntelligence before any producer emits it",
                "consumer-readiness, not product exposure",
                "does not emit engineIntelligence in production runtime",
                "does not add alert-service projection",
                "does not persist engineIntelligence",
                "does not expose engineIntelligence through API/UI",
                "does not add final decisioning",
                "Alert-service may prove deserialization readiness only",
                "Producer emission must be a separate branch",
                "Future producer emission must be disabled by default",
                "guarded by an explicit feature flag",
                "Producer emission requires consumer-readiness proof",
                "Projection requires separate review",
                "Future producer emission of engineIntelligence must be disabled by default",
                "Producer emission requires an explicit rollout flag",
                "Producer emission must not be enabled until FDP-93 consumer-readiness tests are green",
                "Old event shape must remain the default until rollout is explicitly enabled",
                "Producer tests in the future branch must cover enabled and disabled modes",
                "Producer emission must preserve FDP-92 public contract semantics",
                "Producer emission must not introduce final decisioning",
                "Producer emission must not combine projection/API/UI in the same branch unless explicitly scoped and reviewed",
                "FDP-92 proves the DTO is bounded",
                "FDP-93 proves consumers tolerate the bounded DTO",
                "FDP-93 intentionally requires alert-service tolerance for unknown top-level TransactionScoredEvent",
                "fields as a forward-compatibility guardrail, not only for engineIntelligence nested fields",
                "Unknown top-level tolerance does not authorize producers to emit arbitrary fields",
                "Producer branches must still",
                "define exact public payload shape",
                "Consumer tolerance is not producer looseness",
                "Source-scan guards are intentionally strict",
                "consumer inventory review is required",
                "Source-scan guards are not a substitute for architectural review",
                "New TransactionScoredEvent",
                "consumers must be added to the inventory intentionally",
                "TRANSACTION_SCORED_EVENT_CONSUMER_INVENTORY_REVIEW_REQUIRED"
        );
    }

    @Test
    void docsDoNotContainRolloutOverclaims() throws Exception {
        assertThat(EngineIntelligenceFdp93SourceScanSupport.read(
                "docs/architecture/engine_intelligence_consumer_readiness.md"
        )).doesNotContainIgnoringCase(
                "production emission enabled",
                "alert projection ready",
                "UI ready",
                "analyst console ready",
                "final decision source",
                "automatic decline",
                "automatic approve",
                "payment authorization"
        );
    }
}
