package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceLineageProperties;
import com.frauddetection.common.events.source.SourceRawPublicationCipher;
import com.frauddetection.common.events.source.SourceOriginProofs;
import com.frauddetection.common.events.source.SourceOriginProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
@Lazy(false)
@EnableConfigurationProperties({SourceReceiptProperties.class, SourceLineageProperties.class, SourceRawPublicationProperties.class,
        SourceRawPublicationRedriveProperties.class, SourceOriginProperties.class})
public class SourceReceiptConfiguration {
    public SourceReceiptConfiguration(SourceReceiptProperties receipts, SourceLineageProperties lineage,
                                      SourceRawPublicationProperties rawPublications, SourceRawPublicationRedriveProperties redrive,
                                      SourceOriginProperties origin) {
        if (lineage.enabled() && !receipts.enabled()) {
            throw new IllegalArgumentException("SOURCE_LINEAGE_RECEIPT_OWNER_REQUIRED");
        }
        if (lineage.enabled() != rawPublications.enabled()) {
            throw new IllegalArgumentException("SOURCE_RAW_PUBLICATION_LINEAGE_CONFIGURATION_REQUIRED");
        }
        if (lineage.enabled() && !origin.enabled()) {
            throw new IllegalArgumentException("SOURCE_ORIGIN_PROOF_CONFIGURATION_REQUIRED");
        }
        if (redrive.enabled() && !rawPublications.enabled()) {
            throw new IllegalArgumentException("SOURCE_RAW_REDRIVE_REQUIRES_RAW_PUBLICATIONS");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "fraud.source-origin.enabled", havingValue = "true")
    SourceOriginProofs sourceOriginProofs(SourceOriginProperties properties) { return new SourceOriginProofs(properties); }

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
                                              SourceReceiptProperties properties, SourceSignatureVerifier verifier,
                                              ObjectProvider<MongoSourceLineage> lineage, ObjectProvider<SourceRawPublicationCipher> cipher,
                                              SourceRawPublicationProperties rawProperties) {
            return new MongoSourceReceiptStore(client, properties.database(), Clock.systemUTC(),
                    new SourceReceiptStore.Limits(properties.replayHorizon(), properties.receiptRetention(),
                            properties.maxReceipts()), verifier, lineage.getIfAvailable(), cipher.getIfAvailable(), rawProperties);
        }

        @Bean
        @ConditionalOnProperty(name = "fraud.source-raw-publications.enabled", havingValue = "true")
        SourceRawPublicationCipher sourceRawPublicationCipher(SourceRawPublicationProperties properties) {
            return new SourceRawPublicationCipher(properties.activeKeyId(), properties.encryptionKeys());
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fraud.source-raw-publications.enabled", havingValue = "true")
    @EnableScheduling
    static class EnabledRawPublicationRecovery {
        @Bean
        SourceRawPublicationRecovery sourceRawPublicationRecovery(SourceReceiptStore receipts,
                                                                  com.frauddetection.ingest.messaging.TransactionRawEventProducer publisher,
                                                                  SourceRawPublicationProperties properties) {
            return new SourceRawPublicationRecovery(receipts, publisher, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fraud.source-raw-publications.redrive.enabled", havingValue = "true")
    static class EnabledRawPublicationRedrive {
        @Bean
        SourceRawPublicationRedriveRunner sourceRawPublicationRedriveRunner(SourceReceiptStore receipts,
                                                                            SourceRawPublicationRedriveProperties properties,
                                                                            org.springframework.context.ConfigurableApplicationContext context) {
            return new SourceRawPublicationRedriveRunner(receipts, properties, context);
        }
    }
}
