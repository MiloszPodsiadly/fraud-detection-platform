package com.frauddetection.scoring.features;

import com.frauddetection.scoring.context.ScoringContext;

import java.util.Objects;

public final class FeatureSnapshotReaderFactory {

    public FeatureSnapshotReader from(ScoringContext context) {
        Objects.requireNonNull(context, "context is required");
        return new FeatureSnapshotReader(context.featureSnapshot());
    }
}
