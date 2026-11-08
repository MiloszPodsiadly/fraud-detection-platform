package com.frauddetection.scoring.source;

import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.source.SourceLineageProperties;
import com.frauddetection.common.events.source.SourceOccurrenceCipher;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourceOccurrenceProperties.class)
public class SourceOccurrenceConfiguration {
    @Bean
    @ConditionalOnProperty(name = "fraud.source-occurrences.enabled", havingValue = "true")
    MongoSourceOccurrenceStore sourceOccurrenceStore(@Qualifier("sourceLineageMongoClient") MongoClient client,
                                                    SourceLineageProperties source, SourceVerificationProperties policies,
                                                    SourceOccurrenceProperties properties) {
        return new MongoSourceOccurrenceStore(client, source.database(), source.expectedMongoUser(),
                new SourceSignatureVerifier(policies, Clock.systemUTC()), Clock.systemUTC(),
                new SourceOccurrenceCipher(properties.activeKeyId(), properties.encryptionKeys()), properties.payloadRetention());
    }
    @Bean
    @ConditionalOnProperty(name = "fraud.source-occurrences.enabled", havingValue = "true")
    SourceOccurrencePublisher sourceOccurrencePublisher(MongoSourceOccurrenceStore store, TransactionScoredEventPublisher publisher) {
        return new SourceOccurrencePublisher(store, publisher);
    }
}
