package com.frauddetection.common.events.source;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.util.concurrent.TimeUnit;

public final class SourceLineageClients {
    private SourceLineageClients() { }
    public static MongoClient open(SourceLineageProperties properties) {
        try {
            ConnectionString connection = new ConnectionString(properties.mongoUri());
            if (connection.getCredential() == null || !properties.expectedMongoUser().equals(connection.getCredential().getUserName())) {
                throw new IllegalArgumentException("SOURCE_LINEAGE_CREDENTIAL_REQUIRED");
            }
            return MongoClients.create(MongoClientSettings.builder().applyConnectionString(connection).timeout(5, TimeUnit.SECONDS).build());
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("SOURCE_LINEAGE_CONNECTION_CONFIGURATION_INVALID"); }
    }
}
