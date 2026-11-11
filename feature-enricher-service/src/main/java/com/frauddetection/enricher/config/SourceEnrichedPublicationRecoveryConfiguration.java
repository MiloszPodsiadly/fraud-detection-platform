package com.frauddetection.enricher.config;

import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.service.SourceEnrichedPublicationRecovery;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourceEnrichedPublicationRecoveryProperties.class, SourceEnrichedPublicationRedriveProperties.class})
@Import({SourceEnrichedPublicationScheduledConfiguration.class, SourceEnrichedPublicationRedriveConfiguration.class})
public class SourceEnrichedPublicationRecoveryConfiguration {
}

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "fraud.source-enriched-publications.redrive.operator-mode", havingValue = "false", matchIfMissing = true)
@org.springframework.scheduling.annotation.EnableScheduling
class SourceEnrichedPublicationScheduledConfiguration {
    @Bean
    Object sourceEnrichedRecoveryOwnerGuard(com.frauddetection.common.events.source.SourceLineageProperties lineage,
                                            SourceEnrichedPublicationRecoveryProperties recovery) {
        if (lineage.enabled() && !recovery.enabled()) {
            throw new IllegalStateException("SOURCE_ENRICHED_RECOVERY_OWNER_REQUIRED");
        }
        return new Object();
    }

    @Bean
    @ConditionalOnProperty(name = "fraud.source-enriched-publications.recovery.enabled", havingValue = "true")
    SourceEnrichedPublicationRecovery sourceEnrichedPublicationRecovery(SourceLineageAccess lineage,
                                                                        TransactionEnrichedEventPublisher publisher,
                                                                        FeatureStore featureStore,
                                                                        SourceEnrichedPublicationRecoveryProperties properties,
                                                                        com.frauddetection.common.events.source.SourceLineageProperties lineageProperties) {
        if (!lineageProperties.enabled()) { throw new IllegalArgumentException("SOURCE_ENRICHED_RECOVERY_REQUIRES_SOURCE_LINEAGE"); }
        return new SourceEnrichedPublicationRecovery(lineage, publisher, featureStore, properties);
    }

}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fraud.source-enriched-publications.redrive.operator-mode", havingValue = "true")
class SourceEnrichedPublicationRedriveConfiguration {
    @Bean
    @ConditionalOnProperty(name = "fraud.source-enriched-publications.redrive.enabled", havingValue = "true")
    com.frauddetection.enricher.service.SourceEnrichedPublicationRedriveRunner sourceEnrichedPublicationRedriveRunner(
            SourceLineageAccess lineage, SourceEnrichedPublicationRedriveProperties properties,
            org.springframework.context.ConfigurableApplicationContext context,
            com.frauddetection.common.events.source.SourceLineageProperties lineageProperties,
            org.springframework.core.env.Environment environment) {
        if (!properties.enabled() || !properties.operatorMode()) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_OPERATOR_MODE_REQUIRED");
        }
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", ""))) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_REQUIRES_PROTECTED_NON_WEB_PROCESS");
        }
        if (!lineageProperties.enabled()) { throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_REQUIRES_SOURCE_LINEAGE"); }
        return new com.frauddetection.enricher.service.SourceEnrichedPublicationRedriveRunner(lineage, properties, context);
    }

    @Bean
    org.springframework.beans.factory.InitializingBean requireEnabledRedrive(SourceEnrichedPublicationRedriveProperties properties) {
        return () -> {
            if (!properties.enabled() || !properties.operatorMode()) {
                throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_OPERATOR_MODE_REQUIRED");
            }
        };
    }
}
