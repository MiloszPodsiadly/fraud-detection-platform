package com.frauddetection.scoring.context;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringContextTest {

    private final TransactionEnrichedEvent transaction = TransactionFixtures.enrichedTransaction().build();
    private final Instant receivedAt = Instant.parse("2026-05-25T10:15:30Z");

    @Test
    void acceptsValidContextAndPreservesRequiredValues() {
        Map<String, Object> snapshot = Map.of("velocity", 3);

        ScoringContext context = new ScoringContext(
                transaction,
                snapshot,
                ScoringMode.SHADOW,
                "corr-123:attempt.1",
                receivedAt
        );

        assertThat(context.transaction()).isSameAs(transaction);
        assertThat(context.featureSnapshot()).containsExactlyEntriesOf(snapshot);
        assertThat(context.mode()).isEqualTo(ScoringMode.SHADOW);
        assertThat(context.correlationId()).isEqualTo("corr-123:attempt.1");
        assertThat(context.receivedAt()).isEqualTo(receivedAt);
    }

    @Test
    void rejectsMissingRequiredValues() {
        Map<String, Object> snapshot = Map.of();

        assertThatThrownBy(() -> new ScoringContext(null, snapshot, ScoringMode.RULE_BASED, "corr-1", receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScoringContext(transaction, null, ScoringMode.RULE_BASED, "corr-1", receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScoringContext(transaction, snapshot, null, "corr-1", receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScoringContext(transaction, snapshot, ScoringMode.RULE_BASED, "corr-1", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScoringContext(transaction, snapshot, ScoringMode.RULE_BASED, null, receivedAt))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonMachineReadableCorrelationIds() {
        assertThatThrownBy(() -> context(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("corr id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("corr\nid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("corr\tid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("corr\u0000id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> context("request received at gateway"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defensivelyCopiesFeatureSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("velocity", 3);

        ScoringContext context = new ScoringContext(
                transaction,
                snapshot,
                ScoringMode.RULE_BASED,
                "corr-1",
                receivedAt
        );
        snapshot.put("velocity", 99);

        assertThat(context.featureSnapshot()).containsEntry("velocity", 3);
        assertThatThrownBy(() -> context.featureSnapshot().put("score", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesOnlyInternalInputBoundaryComponents() {
        assertThat(Arrays.stream(ScoringContext.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("transaction", "featureSnapshot", "mode", "correlationId", "receivedAt")
                .doesNotContain(
                        "rawPayload",
                        "rawFeatures",
                        "customerPayload",
                        "stackTrace",
                        "exception",
                        "token",
                        "secret",
                        "metadata",
                        "score",
                        "riskLevel",
                        "engineResults"
                );
    }

    private ScoringContext context(String correlationId) {
        return new ScoringContext(transaction, Map.of(), ScoringMode.RULE_BASED, correlationId, receivedAt);
    }
}
