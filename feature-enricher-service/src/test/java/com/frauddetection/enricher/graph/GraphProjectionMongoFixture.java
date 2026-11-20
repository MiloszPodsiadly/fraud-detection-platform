package com.frauddetection.enricher.graph;

import com.github.dockerjava.api.model.HostConfig;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandListener;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Ephemeral authenticated replica set dedicated to Graph schema/RBAC integration tests. */
final class GraphProjectionMongoFixture implements AutoCloseable {
    private final GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
            .withExposedPorts(27017).withStartupTimeout(Duration.ofSeconds(60))
            .withCommand("bash", "-c", "openssl rand -base64 756 > /tmp/graph-key; chmod 400 /tmp/graph-key; "
                    + "exec mongod --replSet graph-rs --bind_ip_all --keyFile /tmp/graph-key --setParameter enableTestCommands=1");
    private final List<MongoClient> clients = new ArrayList<>();

    void start() throws Exception {
        server.start();
        execute("rs.initiate({_id:'graph-rs',members:[{_id:0,host:'localhost:27017'}]})");
        execute("let n=0; while(!db.hello().isWritablePrimary && n++<100){sleep(100)}; "
                + "if(!db.hello().isWritablePrimary){throw Error('primary unavailable')}; "
                + "db.getSiblingDB('admin').createUser({user:'graph_root',pwd:'ephemeral-graph-root',roles:['root']})");
    }

    MongoClient root() { return connect("graph_root", "ephemeral-graph-root", "admin"); }

    String imageName() { return server.getDockerImageName(); }

    String host() { return server.getHost(); }

    int port() { return server.getMappedPort(27017); }

    HostConfig hostConfig() { return server.getDockerClient().inspectContainerCmd(server.getContainerId()).exec().getHostConfig(); }

    MongoClient schemaOwner(MongoClient root, String databaseName) {
        MongoDatabase database = root.getDatabase(databaseName);
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(databaseName, "", List.of("listCollections", "createCollection")));
        privileges.add(privilege(databaseName, GraphProjectionSchema.COLLECTION,
                List.of("find", "listIndexes", "createIndex")));
        return createPrincipal(database, GraphProjectionSchema.SCHEMA_OWNER, privileges);
    }

    MongoClient writer(MongoClient root, String databaseName) {
        MongoDatabase database = root.getDatabase(databaseName);
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(databaseName, "", List.of("listCollections")));
        privileges.add(privilege(databaseName, GraphProjectionSchema.COLLECTION,
                List.of("find", "listIndexes", "insert", "update")));
        return createPrincipal(database, GraphProjectionSchema.WRITER, privileges);
    }

    MongoClient existingWriter(String databaseName) {
        return connect(GraphProjectionSchema.WRITER, "ephemeral-graph-only", databaseName);
    }

    MongoClient reader(MongoClient root, String databaseName) {
        MongoDatabase database = root.getDatabase(databaseName);
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(databaseName, "", List.of("listCollections")));
        privileges.add(privilege(databaseName, GraphProjectionSchema.COLLECTION, List.of("find", "listIndexes")));
        return createPrincipal(database, GraphProjectionSchema.READER, privileges);
    }

    MongoClient readerWithWrongAuthenticationDatabase(String databaseName) {
        return connect(GraphProjectionSchema.READER, "ephemeral-graph-only", "admin");
    }

    MongoClient monitoredReader(String databaseName, CommandListener listener) {
        return connect(GraphProjectionSchema.READER, "ephemeral-graph-only", databaseName, listener);
    }

    private MongoClient createPrincipal(MongoDatabase database, String user, List<Document> privileges) {
        database.runCommand(new Document("createRole", user).append("privileges", privileges).append("roles", List.of()));
        database.runCommand(new Document("createUser", user).append("pwd", "ephemeral-graph-only")
                .append("roles", List.of(user)));
        return connect(user, "ephemeral-graph-only", database.getName());
    }

    private void execute(String javascript) throws Exception {
        var result = server.execInContainer("mongosh", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) { throw new IllegalStateException("GRAPH_TEST_MONGO_BOOTSTRAP_FAILED"); }
    }

    private static Document privilege(String database, String collection, List<String> actions) {
        return new Document("resource", new Document("db", database).append("collection", collection))
                .append("actions", actions);
    }

    private MongoClient connect(String user, String password, String authDatabase) {
        return connect(user, password, authDatabase, null);
    }

    private MongoClient connect(String user, String password, String authDatabase, CommandListener listener) {
        String address = server.getHost() + ":" + server.getMappedPort(27017);
        String uri = "mongodb://" + user + ':' + password + '@' + address
                + "/?directConnection=true&authSource=" + authDatabase;
        MongoClientSettings.Builder settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri)).timeout(5, TimeUnit.SECONDS);
        if (listener != null) { settings.addCommandListener(listener); }
        MongoClient client = MongoClients.create(settings.build());
        clients.add(client);
        return client;
    }

    @Override public void close() {
        clients.forEach(MongoClient::close);
        server.close();
    }
}
