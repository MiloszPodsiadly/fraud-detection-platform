package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;

import java.util.Objects;

/** Explicit deployment-time, additive migration; invoke with the schema-owner Mongo principal. */
public final class SourceOccurrenceRecoveryMigration {
    private SourceOccurrenceRecoveryMigration() { }

    public static void migrateValidator(MongoDatabase database) {
        SourceOccurrenceSchema.migrateRecoveryValidator(Objects.requireNonNull(database), true);
    }
}
