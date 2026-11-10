package com.frauddetection.enricher.service;

import com.frauddetection.common.events.source.SourceLineageAccess;
import com.frauddetection.enricher.config.SourceEnrichedPublicationRedriveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

/** One-shot, non-web operation; launch authorization belongs to protected deployment control-plane RBAC. */
public final class SourceEnrichedPublicationRedriveRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SourceEnrichedPublicationRedriveRunner.class);
    private final SourceLineageAccess lineage;
    private final SourceEnrichedPublicationRedriveProperties properties;
    private final ConfigurableApplicationContext context;

    public SourceEnrichedPublicationRedriveRunner(SourceLineageAccess lineage,
                                                  SourceEnrichedPublicationRedriveProperties properties,
                                                  ConfigurableApplicationContext context) {
        this.lineage = lineage;
        this.properties = properties;
        this.context = context;
    }

    @Override public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type", ""))) {
            throw new IllegalStateException("SOURCE_ENRICHED_REDRIVE_REQUIRES_PROTECTED_NON_WEB_PROCESS");
        }
        var result = lineage.redriveEnrichedPublication(properties.publicationId(), properties.reason());
        if (result.status() != SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED) {
            throw new IllegalStateException("SOURCE_ENRICHED_REDRIVE_" + result.status());
        }
        log.warn("Authorized source ENRICHED publication redrive queued. reason={} recoveryEpoch={}",
                properties.reason(), result.recoveryEpoch());
        context.close();
    }
}
