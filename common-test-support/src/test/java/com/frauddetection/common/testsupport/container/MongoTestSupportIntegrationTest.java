package com.frauddetection.common.testsupport.container;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoTestSupportIntegrationTest {

    private static final String ROOT_USER = "test_root";
    private static final String ROOT_PASSWORD = "ephemeral-root-password-0123456789";

    @Test
    void pinnedImageDeclaresTheMongoCompatibilityRequiredByTestcontainers() {
        DockerImageName image = MongoTestSupport.dockerImageName();

        assertThat(image.asCanonicalNameString()).isEqualTo(MongoTestSupport.MONGODB_IMAGE_REFERENCE);
        assertThat(image.isCompatibleWith(DockerImageName.parse("mongo"))).isTrue();
        assertThatCode(MongoTestSupport::newMongoDBContainer).doesNotThrowAnyException();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void freshAuthenticatedReplicaSetBecomesPrimaryAndRepeatedBootstrapPreservesData() throws Exception {
        GenericContainer<?> mongo = MongoTestSupport.newAuthenticatedMongoContainer(
                "support-rs", ROOT_USER, ROOT_PASSWORD, false);
        try {
            MongoTestSupport.startAuthenticatedReplicaSet(mongo, "support-rs", ROOT_USER, ROOT_PASSWORD);
            runRootCommand(mongo, "db.getSiblingDB('fraud_alerts').getCollection('bootstrap_probe')"
                    + ".insertOne({_id:'preserved',value:1});");

            MongoTestSupport.startAuthenticatedReplicaSet(mongo, "support-rs", ROOT_USER, ROOT_PASSWORD);

            runRootCommand(mongo, "const admin=db.getSiblingDB('admin');"
                    + "const hello=admin.runCommand({hello:1});"
                    + "if(hello.setName!=='support-rs' || !hello.isWritablePrimary) throw Error('not writable PRIMARY');"
                    + "if(db.getSiblingDB('fraud_alerts').getCollection('bootstrap_probe').countDocuments({_id:'preserved'})!==1)"
                    + " throw Error('repeat bootstrap changed data');");
            var anonymous = mongo.execInContainer("mongosh", "--quiet", "--host", "127.0.0.1", "--eval",
                    "db.getSiblingDB('admin').runCommand({listDatabases:1})");
            assertThat(anonymous.getExitCode()).as("anonymous database enumeration must be denied").isNotZero();
        } finally {
            mongo.stop();
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void bootstrapStopsAContainerWhenItFindsAnUnexpectedExistingReplicaSet() throws Exception {
        GenericContainer<?> mongo = MongoTestSupport.newAuthenticatedMongoContainer(
                "wrong-rs", ROOT_USER, ROOT_PASSWORD, false);
        try {
            MongoTestSupport.startAuthenticatedReplicaSet(mongo, "wrong-rs", ROOT_USER, ROOT_PASSWORD);

            assertThatThrownBy(() -> MongoTestSupport.startAuthenticatedReplicaSet(
                    mongo, "expected-rs", ROOT_USER, ROOT_PASSWORD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MongoDB replica set does not match expected 'expected-rs'");
            assertThat(mongo.isRunning()).isFalse();
        } finally {
            if (mongo.isRunning()) {
                mongo.stop();
            }
        }
    }

    private static void runRootCommand(GenericContainer<?> mongo, String javascript) throws Exception {
        var result = mongo.execInContainer("mongosh", "--quiet", "--host", "127.0.0.1",
                "--username", ROOT_USER, "--password", ROOT_PASSWORD,
                "--authenticationDatabase", "admin", "--eval", javascript);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
