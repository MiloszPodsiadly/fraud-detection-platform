package com.frauddetection.scoring.context;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringContextFactoryTest {

    private final ScoringContextFactory factory = new ScoringContextFactory();
    private final Instant receivedAt = Instant.parse("2026-05-25T11:00:00Z");

    @Test
    void createsContextFromExistingRequestWithoutDerivingOutputs() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        FraudScoringRequest request = new FraudScoringRequest(event, Map.of("velocity", 3));

        ScoringContext context = factory.from(request, ScoringMode.COMPARE, receivedAt);

        assertThat(context.transaction()).isSameAs(event);
        assertThat(context.featureSnapshot()).containsExactlyEntriesOf(request.featureSnapshot());
        assertThat(context.mode()).isEqualTo(ScoringMode.COMPARE);
        assertThat(context.correlationId()).isEqualTo(event.correlationId());
        assertThat(context.receivedAt()).isEqualTo(receivedAt);
        assertThat(Arrays.stream(ScoringContext.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("metadata", "score", "riskLevel", "engineResults");
    }

    @Test
    void rejectsMissingRequestInputs() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();

        assertThatThrownBy(() -> factory.from(null, ScoringMode.RULE_BASED, receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.from(new FraudScoringRequest(null, Map.of()), ScoringMode.RULE_BASED, receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.from(new FraudScoringRequest(event, null), ScoringMode.RULE_BASED, receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.from(new FraudScoringRequest(event, Map.of()), null, receivedAt))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.from(new FraudScoringRequest(event, Map.of()), ScoringMode.RULE_BASED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsInvalidCorrelationIdFromTransaction() {
        TransactionEnrichedEvent validEvent = TransactionFixtures.enrichedTransaction().build();
        TransactionEnrichedEvent invalidEvent = new TransactionEnrichedEvent(
                validEvent.eventId(),
                validEvent.transactionId(),
                "not a machine readable correlation id",
                validEvent.customerId(),
                validEvent.accountId(),
                validEvent.createdAt(),
                validEvent.transactionTimestamp(),
                validEvent.transactionAmount(),
                validEvent.merchantInfo(),
                validEvent.deviceInfo(),
                validEvent.locationInfo(),
                validEvent.customerContext(),
                validEvent.recentTransactionCount(),
                validEvent.recentTransactionCountWindow(),
                validEvent.recentAmountSum(),
                validEvent.recentAmountSumWindow(),
                validEvent.transactionVelocityPerMinute(),
                validEvent.merchantFrequency7d(),
                validEvent.deviceNovelty(),
                validEvent.countryMismatch(),
                validEvent.proxyOrVpnDetected(),
                validEvent.featureFlags(),
                validEvent.featureSnapshot()
        );

        assertThatThrownBy(() -> factory.from(
                new FraudScoringRequest(invalidEvent, Map.of()),
                ScoringMode.RULE_BASED,
                receivedAt
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRequestWithNullFeatureSnapshotValue() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("velocity", null);

        assertThatThrownBy(() -> factory.from(
                new FraudScoringRequest(event, snapshot),
                ScoringMode.RULE_BASED,
                receivedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null values");
    }

    @Test
    void rejectsRequestWithNullFeatureSnapshotKey() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(null, 3);

        assertThatThrownBy(() -> factory.from(
                new FraudScoringRequest(event, snapshot),
                ScoringMode.RULE_BASED,
                receivedAt
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null keys");
    }

    @Test
    void documentsTopLevelCopyOnly() {
        TransactionEnrichedEvent event = TransactionFixtures.enrichedTransaction().build();
        List<String> nestedSignals = new ArrayList<>(List.of("velocity"));
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("signals", nestedSignals);

        ScoringContext context = factory.from(
                new FraudScoringRequest(event, snapshot),
                ScoringMode.RULE_BASED,
                receivedAt
        );
        snapshot.put("lateAddition", "ignored");
        nestedSignals.add("new-signal");

        assertThat(context.featureSnapshot()).doesNotContainKey("lateAddition");
        assertThatThrownBy(() -> context.featureSnapshot().put("score", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.featureSnapshot().get("signals")).isSameAs(nestedSignals);
        assertThat(nestedSignals).contains("new-signal");
    }
}
