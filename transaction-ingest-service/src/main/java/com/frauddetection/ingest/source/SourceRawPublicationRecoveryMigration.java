package com.frauddetection.ingest.source;

import com.mongodb.client.MongoDatabase;

import java.util.Objects;

/** Explicit additive deployment migration. Invoke once using the schema-owner Mongo principal. */
public final class SourceRawPublicationRecoveryMigration {
    private SourceRawPublicationRecoveryMigration() { }

    public static void migrateValidator(MongoDatabase database) {
        MongoSourceRawPublicationSchema.migrateRecoveryValidator(Objects.requireNonNull(database), true);
    }
}
