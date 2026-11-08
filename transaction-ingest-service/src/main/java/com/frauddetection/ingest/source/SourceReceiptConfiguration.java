package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceLineageProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@Lazy(false)
@EnableConfigurationProperties({SourceReceiptProperties.class, SourceLineageProperties.class})
public class SourceReceiptConfiguration {
    public SourceReceiptConfiguration(SourceReceiptProperties receipts, SourceLineageProperties lineage) {
        if (lineage.enabled() && !receipts.enabled()) {
            throw new IllegalArgumentException("SOURCE_LINEAGE_RECEIPT_OWNER_REQUIRED");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fraud.source-receipts.enabled", havingValue = "true")
    static class EnabledReceipts {
        @Bean(destroyMethod = "close")
        MongoClient sourceReceiptMongoClient(SourceReceiptProperties properties) {
            try {
                return MongoClients.create(MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(properties.mongoUri()))
                        .timeout(5, TimeUnit.SECONDS).build());
            } catch (RuntimeException invalid) {
                // A driver parse exception must not reflect credential-bearing configuration.
                throw new IllegalArgumentException("SOURCE_RECEIPT_CONNECTION_CONFIGURATION_INVALID");
            }
        }

        @Bean
        SourceReceiptStore sourceReceiptStore(@Qualifier("sourceReceiptMongoClient") MongoClient client,
                                              SourceReceiptProperties properties, SourceSignatureVerifier verifier) {
            return new MongoSourceReceiptStore(client, properties.database(), Clock.systemUTC(),
                    new SourceReceiptStore.Limits(properties.replayHorizon(), properties.receiptRetention(),
                            properties.maxReceipts()), verifier);
        }

        @Bean
        @ConditionalOnProperty(name = "fraud.source-lineage.enabled", havingValue = "true")
        MongoSourceLineage sourceLineageOwner(@Qualifier("sourceReceiptMongoClient") MongoClient client,
                                             SourceReceiptProperties receipts, SourceLineageProperties lineage,
                                             SourceSignatureVerifier verifier) {
            if (!receipts.database().equals(lineage.database()) || lineage.mongoUri() != null
                    && !receipts.mongoUri().equals(lineage.mongoUri())) {
                throw new IllegalArgumentException("SOURCE_LINEAGE_OWNER_CONNECTION_MISMATCH");
            }
            return new MongoSourceLineage(client, receipts.database(), lineage.expectedMongoUser(),
                    MongoSourceLineage.Stage.INGEST, verifier, Clock.systemUTC());
        }
    }
}
