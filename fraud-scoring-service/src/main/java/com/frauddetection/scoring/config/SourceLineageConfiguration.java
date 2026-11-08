package com.frauddetection.scoring.config;

import com.frauddetection.common.events.source.MongoSourceLineage;
import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.common.events.source.SourceLineageClients;
import com.frauddetection.common.events.source.SourceLineageProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourceLineageProperties.class, SourceVerificationProperties.class})
public class SourceLineageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "fraud.source-lineage.enabled", havingValue = "true")
    MongoClient sourceLineageMongoClient(SourceLineageProperties properties) { return SourceLineageClients.open(properties); }

    @Bean
    SourceLineageAccess sourceLineageAccess(SourceLineageProperties properties, SourceVerificationProperties policies,
                                          @Qualifier("sourceLineageMongoClient") ObjectProvider<MongoClient> client) {
        if (!properties.enabled()) { return SourceLineageAccess.disabled(); }
        return new MongoSourceLineage(client.getObject(), properties.database(), properties.expectedMongoUser(),
                MongoSourceLineage.Stage.SCORER, new SourceSignatureVerifier(policies, Clock.systemUTC()), Clock.systemUTC());
    }
}
