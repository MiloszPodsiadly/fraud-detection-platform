package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;

/** Explicit schema-owner operation. Limited ENRICHER credentials cannot run collMod or createIndex. */
public final class SourceEnrichedPublicationRecoveryMigration {
    private SourceEnrichedPublicationRecoveryMigration() { }

    public static void migrate(MongoDatabase database) {
        if (database == null) { throw new IllegalArgumentException("SOURCE_ENRICHED_PUBLICATION_MIGRATION_DATABASE_REQUIRED"); }
        SourceLineageSchema.migrateEnrichedPublicationRecovery(database);
    }
}
