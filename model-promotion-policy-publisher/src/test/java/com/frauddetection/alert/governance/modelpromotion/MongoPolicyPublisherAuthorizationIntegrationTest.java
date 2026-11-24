package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.ClientSession;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoPolicyPublisherAuthorizationIntegrationTest {

    private static final String ROOT_USER = "test_root";
    private static final String ROOT_PASSWORD = "TestMongoRootPassword_0123456789abcdef";
    private static final String GOVERNANCE_PASSWORD = "TestGovernancePassword_0123456789abcdef";
    private static final String TRUST_PASSWORD = "TestTrustPassword_0123456789abcdef";
    private static final String ALERTS_PASSWORD = "TestAlertsPassword_0123456789abcdef";
    private static final String PUBLISHER_PASSWORD = "TestPublisherPassword_0123456789abcdef";
    private static final String IMAGE = "mongo:7.0.48@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1";
    private static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(IMAGE))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MONGODB_GOVERNANCE_PASSWORD", GOVERNANCE_PASSWORD)
            .withEnv("MONGODB_TRUST_AUTHORITY_PASSWORD", TRUST_PASSWORD)
            .withEnv("MONGODB_ALERTS_PASSWORD", ALERTS_PASSWORD)
            .withEnv("MONGODB_POLICY_PUBLISHER_PASSWORD", PUBLISHER_PASSWORD)
            .withEnv("MONGODB_AUTH_MIGRATION", "true");

    @BeforeAll
    static void startMongo() throws Exception {
        MONGO.start();
        MONGO.copyFileToContainer(MountableFile.forHostPath(bootstrapScript()), "/tmp/mongo-init-users.js");
        runRootMongosh("db.getSiblingDB('fraud_alerts').createUser({user:'policy_publisher_app',pwd:'legacy-publisher-password-0123456789',roles:[{role:'readWrite',db:'fraud_alerts'}]});"
                + "db.getSiblingDB('fraud_alerts').getCollection('alerts').insertOne({_id:'preserved-alert',status:'OPEN'});"
                + "db.getSiblingDB('fraud_alerts').getCollection('model_policy_publication_jobs').insertOne({_id:'job-1',state:'CLAIMED'});"
                + "db.getSiblingDB('fraud_alerts').getCollection('alerts').createIndex({status:1},{name:'preserved_status_idx'});");
        runBootstrap();
        runBootstrap();
    }

    @AfterAll
    static void stopMongo() {
        MONGO.stop();
    }

    @Test
    void explicitMigrationCreatesUsersAndNarrowsLegacyPublisherWithoutChangingExistingRecords() throws Exception {
        GenericContainer<?> legacy = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(27017)
                .withCommand("mongod", "--replSet", "auth-upgrade-rs", "--bind_ip_all");
        legacy.start();
        try {
            runLegacyMongosh(legacy, "rs.initiate({_id:'auth-upgrade-rs',members:[{_id:0,host:'localhost:27017'}]});"
                    + "let n=0; while(!db.hello().isWritablePrimary && n++<150){sleep(100)};"
                    + "if(!db.hello().isWritablePrimary) throw Error('replica set did not become primary');"
                    + "let alerts=db.getSiblingDB('fraud_alerts');"
                    + "alerts.createUser({user:'policy_publisher_app',pwd:'legacy-publisher-password-0123456789',roles:[{role:'readWrite',db:'fraud_alerts'}]});"
                    + "alerts.getCollection('alerts').insertOne({_id:'legacy-record',status:'OPEN'});"
                    + "alerts.getCollection('alerts').createIndex({status:1},{name:'legacy_status_idx'});");
            legacy.copyFileToContainer(MountableFile.forHostPath(bootstrapScript()), "/tmp/mongo-init-users.js");
            runLegacyBootstrap(legacy);
            runLegacyBootstrap(legacy);
            runLegacyMongosh(legacy, "let alerts=db.getSiblingDB('fraud_alerts');"
                    + "let root=db.getSiblingDB('admin').getUser('test_root');"
                    + "let publisher=alerts.getUser('policy_publisher_app');"
                    + "if(!root || !root.roles.some(r=>r.role==='root' && r.db==='admin')) throw Error('root bootstrap user missing');"
                    + "if(!publisher || publisher.roles.length!==1 || publisher.roles[0].role!=='policy_publisher_runtime') throw Error('publisher role was not narrowed');"
                    + "if(alerts.getCollection('alerts').findOne({_id:'legacy-record'}).status!=='OPEN') throw Error('existing record changed');"
                    + "if(!alerts.getCollection('alerts').getIndexes().some(i=>i.name==='legacy_status_idx')) throw Error('existing index changed');");
        } finally {
            legacy.stop();
        }
    }

    @Test
    void publisherIsLimitedToPublicationCollectionsAndCanCommitItsAtomicWorkflow() {
        try (MongoClient publisher = client("policy_publisher_app", PUBLISHER_PASSWORD, "fraud_alerts")) {
            var database = publisher.getDatabase("fraud_alerts");
            database.getCollection("model_promotion_policies")
                    .createIndex(new Document("institutional_scope", 1),
                            new com.mongodb.client.model.IndexOptions().name("publisher_scope_idx"));

            try (ClientSession session = publisher.startSession()) {
                session.withTransaction(() -> {
                    database.getCollection("model_policy_publication_jobs")
                            .updateOne(session, eq("_id", "job-1"), set("state", "PUBLISHED"));
                    database.getCollection("model_promotion_policy_publication_fences")
                            .updateOne(session, eq("_id", "scope-1"), inc("generation", 1),
                                    new com.mongodb.client.model.UpdateOptions().upsert(true));
                    database.getCollection("model_promotion_policies")
                            .insertOne(session, new Document("_id", "policy-1"));
                    database.getCollection("model_promotion_policy_approval_evidence")
                            .insertOne(session, new Document("_id", "evidence-1"));
                    return null;
                });
            }

            assertThatThrownBy(() -> database.getCollection("alerts").find().first())
                    .isInstanceOf(MongoException.class);
            assertThatThrownBy(() -> database.getCollection("fraud_cases").insertOne(new Document("_id", "blocked")))
                    .isInstanceOf(MongoException.class);
            assertThatThrownBy(() -> database.runCommand(new Document("dropDatabase", 1)))
                    .isInstanceOf(MongoException.class);
            assertThatThrownBy(() -> database.runCommand(new Document("createUser", "escalated")
                    .append("pwd", "not-a-real-secret")
                    .append("roles", List.of("readWrite"))))
                    .isInstanceOf(MongoException.class);
        }

        try (MongoClient root = rootClient()) {
            var database = root.getDatabase("fraud_alerts");
            assertThat(database.getCollection("alerts").find(eq("_id", "preserved-alert")).first()
                    .getString("status")).isEqualTo("OPEN");
            assertThat(database.getCollection("alerts").listIndexes().into(new java.util.ArrayList<>()))
                    .anyMatch(index -> "preserved_status_idx".equals(index.getString("name")));
            assertThat(database.getCollection("model_policy_publication_jobs").find(eq("_id", "job-1")).first()
                    .getString("state")).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void unauthenticatedAndInvalidCredentialsCannotReadPublisherData() {
        try (MongoClient anonymous = client(null, null, null)) {
            assertThatThrownBy(() -> anonymous.getDatabase("fraud_alerts")
                    .getCollection("model_promotion_policies").find().first())
                    .isInstanceOf(MongoException.class);
        }
        try (MongoClient invalid = client("policy_publisher_app", "wrong-password", "fraud_alerts")) {
            assertThatThrownBy(() -> invalid.getDatabase("fraud_alerts")
                    .getCollection("model_promotion_policies").find().first())
                    .isInstanceOf(MongoException.class);
        }
    }

    @Test
    void effectivePublisherRoleContainsOnlyItsFourRequiredCollections() {
        try (MongoClient root = rootClient()) {
            Document response = root.getDatabase("fraud_alerts").runCommand(new Document("rolesInfo",
                    new Document("role", "policy_publisher_runtime").append("db", "fraud_alerts"))
                    .append("showPrivileges", true));
            List<Document> roles = response.getList("roles", Document.class);
            assertThat(roles).hasSize(1);
            List<Document> privileges = roles.getFirst().getList("privileges", Document.class);
            assertThat(privileges).hasSize(4);
            assertThat(privileges).allSatisfy(privilege -> {
                Document resource = privilege.get("resource", Document.class);
                assertThat(resource.getString("db")).isEqualTo("fraud_alerts");
                assertThat(resource.getString("collection")).isNotBlank();
            });
            assertThat(privileges).extracting(privilege -> privilege.get("resource", Document.class)
                    .getString("collection")).containsExactlyInAnyOrder(
                    "model_policy_publication_jobs",
                    "model_promotion_policies",
                    "model_promotion_policy_approval_evidence",
                    "model_promotion_policy_publication_fences");
            assertThat(roles.getFirst().getList("inheritedRoles", Document.class)).isEmpty();
        }
    }

    private static void runBootstrap() throws Exception {
        runMongosh("load('/tmp/mongo-init-users.js')");
    }

    private static void runRootMongosh(String javascript) throws Exception {
        var result = MONGO.execInContainer("mongosh", "--username", ROOT_USER, "--password", ROOT_PASSWORD,
                "--authenticationDatabase", "admin", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Mongo authorization fixture failed (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static void runMongosh(String javascript) throws Exception {
        var result = MONGO.execInContainer("mongosh", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Mongo authorization fixture failed (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static void runLegacyMongosh(GenericContainer<?> legacy, String javascript) throws Exception {
        var result = legacy.execInContainer("mongosh", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Legacy Mongo fixture failed (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static void runLegacyBootstrap(GenericContainer<?> legacy) throws Exception {
        String command = "MONGO_INITDB_ROOT_USERNAME='" + ROOT_USER + "' "
                + "MONGO_INITDB_ROOT_PASSWORD='" + ROOT_PASSWORD + "' "
                + "MONGODB_GOVERNANCE_PASSWORD='" + GOVERNANCE_PASSWORD + "' "
                + "MONGODB_TRUST_AUTHORITY_PASSWORD='" + TRUST_PASSWORD + "' "
                + "MONGODB_ALERTS_PASSWORD='" + ALERTS_PASSWORD + "' "
                + "MONGODB_POLICY_PUBLISHER_PASSWORD='" + PUBLISHER_PASSWORD + "' "
                + "MONGODB_AUTH_MIGRATION=true mongosh --quiet --eval 'load(\"/tmp/mongo-init-users.js\")'";
        var result = legacy.execInContainer("bash", "-lc", command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Legacy Mongo migration failed (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static Path bootstrapScript() throws Exception {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("deployment/mongo-init-users.js");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate deployment/mongo-init-users.js from the test working directory");
    }

    private static MongoClient rootClient() {
        return client(ROOT_USER, ROOT_PASSWORD, "admin");
    }

    private static MongoClient client(String username, String password, String authDatabase) {
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO.getReplicaSetUrl("fraud_alerts")));
        if (username != null) {
            settings.credential(MongoCredential.createCredential(username, authDatabase, password.toCharArray()));
        }
        return MongoClients.create(settings.build());
    }
}
