package com.frauddetection.scoring.source;

import com.frauddetection.common.events.source.MongoSourceOccurrenceStore;
import com.frauddetection.common.events.source.SourceOccurrenceCipher;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SourceOccurrenceConfigurationTest {
    @Test void defaultRuntimeHasNoOutboxConnectionKeyOrPublisher() {
        new ApplicationContextRunner().withUserConfiguration(SourceOccurrenceConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MongoClient.class).doesNotHaveBean(MongoSourceOccurrenceStore.class)
                    .doesNotHaveBean(SourceOccurrencePublisher.class);
            assertThat(context.getBean(SourceOccurrenceProperties.class).encryptionKeys()).isEmpty();
        });
    }
    @Test void enablingRequiresExplicitStorageKeyAndRetention() {
        new ApplicationContextRunner().withUserConfiguration(SourceOccurrenceConfiguration.class)
                .withPropertyValues("fraud.source-occurrences.enabled=true").run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new SourceOccurrenceCipher(null, Map.of())).hasMessage("SOURCE_OCCURRENCE_ENCRYPTION_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> new SourceOccurrenceCipher("caller", Map.of("caller", "invalid")))
                .hasMessage("SOURCE_OCCURRENCE_ENCRYPTION_CONFIGURATION_INVALID");
    }
}
