package com.frauddetection.common.events.source;

import com.mongodb.client.MongoDatabase;

import java.time.Clock;

/** Owner-run forward migration for durable per-RAW terminal outcomes. */
public final class SourceRawTerminalDispositionMigration {
    private SourceRawTerminalDispositionMigration() { }

    public static void migrate(MongoDatabase database, Clock clock) {
        SourceRawTerminalDispositionSchema.migrate(database, clock);
    }
}
