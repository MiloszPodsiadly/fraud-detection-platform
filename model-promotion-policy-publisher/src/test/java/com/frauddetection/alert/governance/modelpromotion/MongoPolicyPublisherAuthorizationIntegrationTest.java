package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.common.testsupport.container.MongoTestSupport;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.ClientSession;
import org.testcontainers.containers.GenericContainer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
    private static final DockerImageName IMAGE = MongoTestSupport.dockerImageName();
    private static final GenericContainer<?> MONGO = MongoTestSupport.newAuthenticatedMongoContainer(
            "rs0", ROOT_USER, ROOT_PASSWORD, false)
            .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MONGODB_GOVERNANCE_PASSWORD", GOVERNANCE_PASSWORD)
            .withEnv("MONGODB_TRUST_AUTHORITY_PASSWORD", TRUST_PASSWORD)
            .withEnv("MONGODB_ALERTS_PASSWORD", ALERTS_PASSWORD)
            .withEnv("MONGODB_POLICY_PUBLISHER_PASSWORD", PUBLISHER_PASSWORD)
            .withEnv("MONGODB_AUTH_MIGRATION", "true");

    @BeforeAll
    static void startMongo() throws Exception {
        MongoTestSupport.startAuthenticatedReplicaSet(MONGO, "rs0", ROOT_USER, ROOT_PASSWORD);
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
    void migrationPreservesOneVolumeAcrossPreAuthMigrationSecuredRestartAndRejectsWrongRootPassword() throws Exception {
        String volumeName = createMigrationTestVolume();
        Network network = null;
        GenericContainer<?> legacy = null;
        GenericContainer<?> secured = null;
        try {
            network = Network.newNetwork();
            legacy = new GenericContainer<>(IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("mongodb")
                    .withCreateContainerCmdModifier(command -> addNamedVolumeMount(command, volumeName))
                    .withCommand("mongod", "--replSet", "rs0", "--bind_ip_all")
                    .waitingFor(Wait.forListeningPort());
            legacy.start();
            runLegacyMongosh(legacy, "rs.initiate({_id:'rs0',members:[{_id:0,host:'mongodb:27017'}]});"
                    + "let n=0; while(!db.hello().isWritablePrimary && n++<150){sleep(100)};"
                    + "if(!db.hello().isWritablePrimary) throw Error('replica set did not become primary');"
                    + "db.getSiblingDB('admin').createUser({user:'test_root',pwd:'PartialLegacyRootPassword_0123456789abcdef',roles:[{role:'readWriteAnyDatabase',db:'admin'}]});"
                    + "let alerts=db.getSiblingDB('fraud_alerts');"
                    + "alerts.createCollection('alerts',{validator:{$jsonSchema:{bsonType:'object',required:['status'],properties:{status:{bsonType:'string'}}}}});"
                    + "alerts.createUser({user:'policy_publisher_app',pwd:'legacy-publisher-password-0123456789',roles:[{role:'readWrite',db:'fraud_alerts'}]});"
                    + "alerts.getCollection('alerts').insertOne({_id:'legacy-record',status:'OPEN'});"
                    + "alerts.getCollection('model_policy_publication_jobs').insertOne({_id:'legacy-job',state:'CLAIMED'});"
                    + "alerts.getCollection('alerts').createIndex({status:1},{name:'legacy_status_idx'});");
            runMigrationService(network, ROOT_PASSWORD, false);
            runMigrationService(network, ROOT_PASSWORD, false);

            legacy.stop();
            secured = securedMongo(network, volumeName);
            secured.start();
            runMigrationService(network, ROOT_PASSWORD, true);
            runPublisherTransaction(secured);
            assertSecuredMongoPreservesMigrationState(secured);
            assertMigrationRejectsWrongRootPassword(network, secured);
            secured.stop();

            secured = securedMongo(network, volumeName);
            secured.start();
            assertSecuredMongoPreservesMigrationState(secured);
        } finally {
            try {
                if (secured != null && secured.isRunning()) secured.stop();
            } finally {
                try {
                    if (legacy != null && legacy.isRunning()) legacy.stop();
                } finally {
                    try {
                        if (network != null) network.close();
                    } finally {
                        removeMigrationTestVolume(volumeName);
                    }
                }
            }
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

            var trustObservations = database.getCollection("model_policy_trust_instance_observations");
            trustObservations.updateOne(eq("_id", "publisher-observation"),
                    new Document("$set", new Document("role", "PUBLISHER").append("generation", 3L)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
            assertThat(trustObservations.find(eq("_id", "publisher-observation")).first()
                    .getString("role")).isEqualTo("PUBLISHER");

            assertThatThrownBy(() -> database.getCollection("alerts").find().first())
                    .isInstanceOf(MongoException.class);
            assertThatThrownBy(() -> database.getCollection("alerts").updateOne(eq("_id", "preserved-alert"),
                    set("status", "COMPROMISED")))
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
    void effectivePublisherRoleContainsOnlyItsFiveRequiredCollections() {
        try (MongoClient root = rootClient()) {
            Document response = root.getDatabase("fraud_alerts").runCommand(new Document("rolesInfo",
                    new Document("role", "policy_publisher_runtime").append("db", "fraud_alerts"))
                    .append("showPrivileges", true));
            List<Document> roles = response.getList("roles", Document.class);
            assertThat(roles).hasSize(1);
            List<Document> privileges = roles.getFirst().getList("privileges", Document.class);
            assertThat(privileges).hasSize(5);
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
                    "model_promotion_policy_publication_fences",
                    "model_policy_trust_instance_observations");
            assertThat(privileges).filteredOn(privilege -> "model_policy_trust_instance_observations".equals(
                    privilege.get("resource", Document.class).getString("collection")))
                    .singleElement().extracting(privilege -> privilege.getList("actions", String.class))
                    .isEqualTo(List.of("find", "insert", "update"));
            assertThat(roles.getFirst().getList("inheritedRoles", Document.class)).isEmpty();
        }
    }

    private static void runBootstrap() throws Exception {
        runRootMongosh("load('/tmp/mongo-init-users.js')");
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

    private static void assertMigrationRejectsWrongRootPassword(Network network, GenericContainer<?> secured) throws Exception {
        assertThatThrownBy(() -> runMigrationService(network, "WrongMongoRootPassword_0123456789abcdef", true))
                .as("auth migration must reject invalid existing root credentials")
                .isInstanceOf(RuntimeException.class);
        runRootMongosh(secured, "let alerts=db.getSiblingDB('fraud_alerts');"
                + "if(alerts.getCollection('alerts').findOne({_id:'legacy-record'}).status!=='OPEN') throw Error('wrong-password migration changed existing data');");
    }

    private static void runMigrationService(Network network, String rootPassword, boolean authenticate) throws Exception {
        try (GenericContainer<?> migration = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withCopyFileToContainer(MountableFile.forHostPath(bootstrapScript()),
                        "/docker-entrypoint-initdb.d/10-create-app-users.js")
                .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", rootPassword)
                .withEnv("MONGODB_GOVERNANCE_PASSWORD", GOVERNANCE_PASSWORD)
                .withEnv("MONGODB_TRUST_AUTHORITY_PASSWORD", TRUST_PASSWORD)
                .withEnv("MONGODB_ALERTS_PASSWORD", ALERTS_PASSWORD)
                .withEnv("MONGODB_POLICY_PUBLISHER_PASSWORD", PUBLISHER_PASSWORD)
                .withEnv("MONGODB_AUTH_MIGRATION", "true")
                .withCommand(migrationCommand(rootPassword, authenticate))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(90)))) {
            migration.start();
        }
    }

    private static String[] migrationCommand(String rootPassword, boolean authenticate) throws Exception {
        List<String> command = new ArrayList<>(List.of("mongosh", "--host", "mongodb"));
        if (authenticate) {
            command.addAll(List.of("--username", ROOT_USER, "--password", rootPassword,
                    "--authenticationDatabase", "admin"));
        }
        command.addAll(List.of("--quiet", "--file", "/docker-entrypoint-initdb.d/10-create-app-users.js"));
        return command.toArray(String[]::new);
    }

    private static void runRootMongosh(GenericContainer<?> mongo, String javascript) throws Exception {
        var result = mongo.execInContainer("mongosh", "--host", "mongodb", "--username", ROOT_USER,
                "--password", ROOT_PASSWORD, "--authenticationDatabase", "admin", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Authenticated Mongo verification failed (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static GenericContainer<?> securedMongo(Network network, String volumeName) throws Exception {
        return new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases("mongodb")
                .withFileSystemBind(mongodbEntrypointScript().toString(), "/opt/mongodb-entrypoint.sh", BindMode.READ_ONLY)
                .withFileSystemBind(bootstrapScript().toString(), "/docker-entrypoint-initdb.d/10-create-app-users.js", BindMode.READ_ONLY)
                .withCreateContainerCmdModifier(command -> {
                    command.withEntrypoint("/bin/bash", "/opt/mongodb-entrypoint.sh");
                    addNamedVolumeMount(command, volumeName);
                })
                .withEnv("MONGO_INITDB_ROOT_USERNAME", ROOT_USER)
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", ROOT_PASSWORD)
                .withEnv("MONGODB_GOVERNANCE_PASSWORD", GOVERNANCE_PASSWORD)
                .withEnv("MONGODB_TRUST_AUTHORITY_PASSWORD", TRUST_PASSWORD)
                .withEnv("MONGODB_ALERTS_PASSWORD", ALERTS_PASSWORD)
                .withEnv("MONGODB_POLICY_PUBLISHER_PASSWORD", PUBLISHER_PASSWORD)
                .withExposedPorts(27017)
                .waitingFor(Wait.forListeningPort());
    }

    private static void addNamedVolumeMount(CreateContainerCmd command, String volumeName) {
        var hostConfig = command.getHostConfig();
        var binds = new ArrayList<>(Arrays.asList(hostConfig.getBinds()));
        binds.add(Bind.parse(volumeName + ":/data/db:rw"));
        hostConfig.withBinds(binds);
    }

    private static void assertSecuredMongoPreservesMigrationState(GenericContainer<?> mongo) throws Exception {
        runRootMongosh(mongo, "let admin=db.getSiblingDB('admin');"
                + "let alerts=db.getSiblingDB('fraud_alerts');"
                + "let root=admin.getUser('test_root');"
                + "let publisher=alerts.getUser('policy_publisher_app');"
                + "if(!root || !root.roles.some(r=>r.role==='root' && r.db==='admin')) throw Error('root user missing after volume recreation');"
                + "if(!publisher || publisher.roles.length!==1 || publisher.roles[0].role!=='policy_publisher_runtime') throw Error('publisher role was not narrowed');"
                + "if(!db.getSiblingDB('fraud_governance').getUser('governance_app')) throw Error('governance account missing');"
                + "if(!db.getSiblingDB('fraud_trust_authority').getUser('trust_authority_app')) throw Error('trust authority account missing');"
                + "if(!alerts.getUser('alert_app')) throw Error('alert service account missing');"
                + "if(alerts.getCollection('alerts').findOne({_id:'legacy-record'}).status!=='OPEN') throw Error('existing record changed');"
                + "if(alerts.getCollection('alerts').countDocuments()!==1) throw Error('existing alert count changed');"
                + "if(alerts.getCollection('model_policy_publication_jobs').findOne({_id:'legacy-job'}).state!=='PUBLISHED') throw Error('transactional publication did not persist');"
                + "if(alerts.getCollection('model_policy_publication_jobs').countDocuments()!==1) throw Error('existing job count changed');"
                + "let index=alerts.getCollection('alerts').getIndexes().find(i=>i.name==='legacy_status_idx');"
                + "if(!index || index.key.status!==1 || Object.keys(index.key).length!==1) throw Error('existing index definition changed');"
                + "let info=alerts.getCollectionInfos({name:'alerts'})[0];"
                + "if(info.options.validator.$jsonSchema.bsonType!=='object' || info.options.validator.$jsonSchema.required.length!==1 || info.options.validator.$jsonSchema.required[0]!=='status' || info.options.validator.$jsonSchema.properties.status.bsonType!=='string') throw Error('existing validator changed');"
                + "if(db.hello().setName!=='rs0') throw Error('replica set config did not survive volume recreation');"
                + "let roleInfo=alerts.runCommand({rolesInfo:{role:'policy_publisher_runtime',db:'fraud_alerts'},showPrivileges:true}).roles;"
                + "if(!roleInfo || roleInfo.length!==1 || roleInfo[0].privileges.length!==5 || roleInfo[0].inheritedRoles.length!==0) throw Error('publisher role is not restricted to five collections');"
                + "let collections=roleInfo[0].privileges.map(p=>p.resource.collection).sort();"
                + "if(JSON.stringify(collections)!==JSON.stringify(['model_policy_publication_jobs','model_promotion_policies','model_promotion_policy_approval_evidence','model_promotion_policy_publication_fences','model_policy_trust_instance_observations'].sort())) throw Error('publisher role collection scope changed');"
                + "if(!alerts.getCollection('model_promotion_policies').findOne({_id:'migration-policy'})) throw Error('publisher transaction policy missing');"
                + "if(!alerts.getCollection('model_promotion_policy_approval_evidence').findOne({_id:'migration-evidence'})) throw Error('publisher transaction evidence missing');"
                + "if(!alerts.getCollection('model_promotion_policy_publication_fences').findOne({_id:'migration-scope'})) throw Error('publisher transaction fence missing');"
                + "if(!alerts.getCollection('model_policy_trust_instance_observations').findOne({_id:'publisher-observation'})) throw Error('publisher trust observation missing');");
        var anonymous = mongo.execInContainer("mongosh", "--host", "mongodb", "--quiet", "--eval",
                "db.getSiblingDB('fraud_alerts').getCollection('alerts').findOne()");
        assertThat(anonymous.getExitCode()).as("secured Mongo must reject anonymous access").isNotZero();
        var invalid = mongo.execInContainer("mongosh", "--host", "mongodb", "--username", "policy_publisher_app",
                "--password", "WrongPublisherPassword_0123456789abcdef", "--authenticationDatabase", "fraud_alerts",
                "--quiet", "--eval", "db.getSiblingDB('fraud_alerts').getCollection('model_promotion_policies').findOne()");
        assertThat(invalid.getExitCode()).as("secured Mongo must reject invalid application credentials").isNotZero();
    }

    private static void runPublisherTransaction(GenericContainer<?> mongo) throws Exception {
        var result = mongo.execInContainer("mongosh", "--host", "mongodb", "--username", "policy_publisher_app",
                "--password", PUBLISHER_PASSWORD, "--authenticationDatabase", "fraud_alerts", "--quiet", "--eval",
                "let session=db.getMongo().startSession();"
                        + "let alerts=session.getDatabase('fraud_alerts');"
                        + "session.startTransaction();"
                        + "try {"
                        + "alerts.getCollection('model_policy_publication_jobs').updateOne({_id:'legacy-job'},{$set:{state:'PUBLISHED'}});"
                        + "alerts.getCollection('model_promotion_policy_publication_fences').updateOne({_id:'migration-scope'},{$inc:{generation:1}},{upsert:true});"
                        + "alerts.getCollection('model_promotion_policies').insertOne({_id:'migration-policy'});"
                        + "alerts.getCollection('model_promotion_policy_approval_evidence').insertOne({_id:'migration-evidence'});"
                        + "session.commitTransaction();"
                        + "} catch(error) { session.abortTransaction(); throw error; } finally { session.endSession(); }"
                        + "db.getSiblingDB('fraud_alerts').getCollection('model_policy_trust_instance_observations').updateOne({_id:'publisher-observation'},{$set:{role:'PUBLISHER',generation:3}},{upsert:true});"
                        + "if(!db.getSiblingDB('fraud_alerts').getCollection('model_policy_trust_instance_observations').findOne({_id:'publisher-observation'})) throw Error('publisher could not read its trust observation');");
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Publisher transaction failed on migrated volume (exit " + result.getExitCode() + "): "
                    + result.getStderr());
        }
    }

    private static String createMigrationTestVolume() throws Exception {
        String name = "fdp157-mongo-auth-upgrade-" + UUID.randomUUID().toString().replace("-", "");
        var result = docker("volume", "create", name);
        if (result.exitCode() != 0 || !result.output().trim().equals(name)) {
            throw new IllegalStateException("Could not create isolated Mongo migration test volume: " + result.output());
        }
        return name;
    }

    private static void removeMigrationTestVolume(String name) throws Exception {
        if (name == null || !name.matches("fdp157-mongo-auth-upgrade-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Refusing to remove a volume not created by this Mongo migration test");
        }
        var result = docker("volume", "rm", name);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Could not remove isolated Mongo migration test volume: " + result.output());
        }
    }

    private static DockerResult docker(String... arguments) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new DockerResult(process.waitFor(), output);
    }

    private record DockerResult(int exitCode, String output) {
    }

    private static Path bootstrapScript() throws Exception {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("deployment/mongo-init-users.js");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate deployment/mongo-init-users.js from the test working directory");
    }

    private static Path mongodbEntrypointScript() throws Exception {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("deployment/mongodb-entrypoint.sh");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate deployment/mongodb-entrypoint.sh from the test working directory");
    }

    private static MongoClient rootClient() {
        return client(ROOT_USER, ROOT_PASSWORD, "admin");
    }

    private static MongoClient client(String username, String password, String authDatabase) {
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + MONGO.getHost() + ":"
                        + MONGO.getMappedPort(27017) + "/fraud_alerts?directConnection=true&authSource=admin"));
        if (username != null) {
            settings.credential(MongoCredential.createCredential(username, authDatabase, password.toCharArray()));
        }
        return MongoClients.create(settings.build());
    }
}
