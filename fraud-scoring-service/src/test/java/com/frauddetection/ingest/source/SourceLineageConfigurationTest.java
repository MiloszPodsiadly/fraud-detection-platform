package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.*;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class SourceLineageConfigurationTest {
    @ParameterizedTest
    @ValueSource(classes = {com.frauddetection.enricher.config.SourceLineageConfiguration.class,
            com.frauddetection.scoring.config.SourceLineageConfiguration.class})
    void defaultRuntimeHasNoConnectionOrSourceGrant(Class<?> configuration) {
        new ApplicationContextRunner().withUserConfiguration(configuration).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MongoClient.class);
            assertThat(context.getBean(SourceVerificationProperties.class).keys()).isEmpty();
            assertThat(context.getBean(SourceLineageAccess.class).enriched(null).state()).isEqualTo(SourceLineage.State.UNVERIFIED);
        });
    }

    @ParameterizedTest
    @ValueSource(classes = {com.frauddetection.enricher.config.SourceLineageConfiguration.class,
            com.frauddetection.scoring.config.SourceLineageConfiguration.class})
    void enablingWithoutIndependentAuthenticatedServiceConfigurationFailsClosed(Class<?> configuration) {
        new ApplicationContextRunner().withUserConfiguration(configuration)
                .withPropertyValues("fraud.source-lineage.enabled=true").run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(configuration)
                .withPropertyValues("fraud.source-lineage.enabled=true", "fraud.source-lineage.database=lineage",
                        "fraud.source-lineage.expected-mongo-user=scorer", "fraud.source-lineage.mongo-uri=mongodb://localhost:27017")
                .run(context -> assertThat(context).hasFailed());
    }
}
