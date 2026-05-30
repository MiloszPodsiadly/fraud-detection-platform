package com.frauddetection.scoring.features;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.context.ScoringContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSnapshotReaderFactoryTest {

    private final FeatureSnapshotReaderFactory factory = new FeatureSnapshotReaderFactory();

    @Test
    void createsReaderFromContextWithoutChangingReaderSemantics() {
        TransactionEnrichedEvent transaction = TransactionFixtures.enrichedTransaction().build();
        ScoringContext context = new ScoringContext(
                transaction,
                Map.of(FraudFeatureContract.DEVICE_NOVELTY, true),
                ScoringMode.RULE_BASED,
                "corr-1",
                Instant.parse("2026-05-25T12:00:00Z")
        );

        FeatureSnapshotReader reader = factory.from(context);

        assertThat(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY).status())
                .isEqualTo(FeatureSnapshotValueStatus.PRESENT);
        assertThat(reader.booleanValue(FraudFeatureContract.DEVICE_NOVELTY).value()).isTrue();
        assertThat(FeatureSnapshotReader.class.getDeclaredMethods())
                .noneMatch(method -> Map.class.isAssignableFrom(method.getReturnType()));
    }

    @Test
    void rejectsMissingContext() {
        assertThatThrownBy(() -> factory.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context is required");
    }
}
