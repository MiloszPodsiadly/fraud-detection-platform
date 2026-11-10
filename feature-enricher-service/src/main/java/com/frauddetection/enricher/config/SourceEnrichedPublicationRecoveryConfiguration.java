package com.frauddetection.enricher.config;

import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.service.SourceEnrichedPublicationRecovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({SourceEnrichedPublicationRecoveryProperties.class, SourceEnrichedPublicationRedriveProperties.class})
public class SourceEnrichedPublicationRecoveryConfiguration {
    @Bean
    @ConditionalOnProperty(name = "fraud.source-enriched-publications.recovery.enabled", havingValue = "true")
    SourceEnrichedPublicationRecovery sourceEnrichedPublicationRecovery(SourceLineageAccess lineage,
                                                                        TransactionEnrichedEventPublisher publisher,
                                                                        SourceEnrichedPublicationRecoveryProperties properties,
                                                                        com.frauddetection.common.events.source.SourceLineageProperties lineageProperties) {
        if (!lineageProperties.enabled()) { throw new IllegalArgumentException("SOURCE_ENRICHED_RECOVERY_REQUIRES_SOURCE_LINEAGE"); }
        return new SourceEnrichedPublicationRecovery(lineage, publisher, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "fraud.source-enriched-publications.redrive.enabled", havingValue = "true")
    com.frauddetection.enricher.service.SourceEnrichedPublicationRedriveRunner sourceEnrichedPublicationRedriveRunner(
            SourceLineageAccess lineage, SourceEnrichedPublicationRedriveProperties properties,
            org.springframework.context.ConfigurableApplicationContext context,
            com.frauddetection.common.events.source.SourceLineageProperties lineageProperties) {
        if (!lineageProperties.enabled()) { throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_REQUIRES_SOURCE_LINEAGE"); }
        return new com.frauddetection.enricher.service.SourceEnrichedPublicationRedriveRunner(lineage, properties, context);
    }
}
