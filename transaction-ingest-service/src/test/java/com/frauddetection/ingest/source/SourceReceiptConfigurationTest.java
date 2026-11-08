package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SourceReceiptConfigurationTest {
    @Test void enablingLineageWithoutItsReceiptOwnerFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(SourceReceiptConfiguration.class)
                .withPropertyValues("fraud.source-lineage.enabled=true", "fraud.source-lineage.database=source_test",
                        "fraud.source-lineage.expected-mongo-user=source_owner")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("SOURCE_LINEAGE_RECEIPT_OWNER_REQUIRED");
                });
    }

    @Test void ordinaryDefaultRuntimeDoesNotCreateAnyMongoConnectionOrAdmissionStore() {
        new ApplicationContextRunner().withUserConfiguration(SourceReceiptConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MongoClient.class).doesNotHaveBean(SourceReceiptStore.class);
        });
    }
    @Test void enablingReceiptsWithoutExplicitStorageAndRetentionConfigurationFailsClosed() {
        new ApplicationContextRunner().withUserConfiguration(SourceReceiptConfiguration.class)
                .withBean(SourceSignatureVerifier.class, () -> new SourceSignatureVerifier(
                        new SourceVerificationProperties(null), java.time.Clock.systemUTC()))
                .withPropertyValues("fraud.source-receipts.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test void invalidConnectionConfigurationDoesNotReflectCredentialMaterial() {
        new ApplicationContextRunner().withUserConfiguration(SourceReceiptConfiguration.class)
                .withBean(SourceSignatureVerifier.class, () -> new SourceSignatureVerifier(
                        new SourceVerificationProperties(null), java.time.Clock.systemUTC()))
                .withPropertyValues("fraud.source-receipts.enabled=true", "fraud.source-receipts.mongo-uri=invalid://credential-canary",
                        "fraud.source-receipts.database=source_test", "fraud.source-receipts.replay-horizon=PT5M",
                        "fraud.source-receipts.receipt-retention=PT10M", "fraud.source-receipts.max-receipts=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure != null) {
                        assertThat(failure.getMessage()).doesNotContain("credential-canary");
                        failure = failure.getCause();
                    }
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("SOURCE_RECEIPT_CONNECTION_CONFIGURATION_INVALID");
                });
    }
}
