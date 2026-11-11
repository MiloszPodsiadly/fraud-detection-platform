package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;

import java.time.Clock;

/** Owner-run schema install; it refuses to cover unresolved work admitted before the gate existed. */
public final class SourceFeatureOrderGateMigration {
    private SourceFeatureOrderGateMigration() { }

    public static void migrate(MongoDatabase database, Clock clock) {
        SourceFeatureOrderGateSchema.initialize(database, true, clock);
    }
}
