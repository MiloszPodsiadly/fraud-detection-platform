package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.features.FeatureSnapshotReader;
import com.frauddetection.scoring.features.FeatureSnapshotReaderFactory;

import java.util.Objects;

final class VelocityFeatureReader {
    private final FeatureSnapshotReaderFactory readerFactory;

    VelocityFeatureReader(FeatureSnapshotReaderFactory readerFactory) {
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory is required");
    }

    VelocityInputs read(ScoringContext context) {
        FeatureSnapshotReader reader = readerFactory.from(context);
        return new VelocityInputs(
                reader.integerValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT),
                reader.stringValue(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW),
                reader.decimalValue(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN),
                reader.doubleValue(FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE)
        );
    }
}
