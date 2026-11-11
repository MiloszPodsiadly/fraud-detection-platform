package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;

import java.time.Clock;

/** Explicit owner-run backfill for the receipt-retained scoped business identity fence. */
public final class SourceEnrichedBusinessIdentityMigration {
    private SourceEnrichedBusinessIdentityMigration() { }

    public static void migrate(MongoDatabase database, SourceEnrichedPublicationCipher cipher, Clock clock) {
        SourceEnrichedBusinessIdentitySchema.migrate(database, cipher, clock);
    }
}
