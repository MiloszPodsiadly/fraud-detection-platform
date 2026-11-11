package com.frauddetection.ingest.source;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real replica set with authentication enabled; credentials exist only in this ephemeral test server. */
final class AuthenticatedSourceMongo implements AutoCloseable {
    private final GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
            .withExposedPorts(27017).withStartupTimeout(Duration.ofSeconds(60))
            .withCommand("bash", "-c", "openssl rand -base64 756 > /tmp/lineage-key; chmod 400 /tmp/lineage-key; "
                    + "exec mongod --replSet lineage-rs --bind_ip_all --keyFile /tmp/lineage-key");
    private final List<MongoClient> clients = new ArrayList<>();

    void start() throws Exception {
        server.start();
        execute("rs.initiate({_id:'lineage-rs',members:[{_id:0,host:'localhost:27017'}]})");
        execute("let n=0; while(!db.hello().isWritablePrimary && n++<100){sleep(100)}; "
                + "if(!db.hello().isWritablePrimary){throw Error('primary unavailable')}; "
                + "db.getSiblingDB('admin').createUser({user:'source_owner',pwd:'ephemeral-owner-only',roles:['root']})");
    }

    private void execute(String javascript) throws Exception {
        var result = server.execInContainer("mongosh", "--quiet", "--eval", javascript);
        if (result.getExitCode() != 0) { throw new IllegalStateException("Authenticated test Mongo bootstrap failed: " + result.getStderr()); }
    }

    MongoClient owner() { return connect("source_owner", "ephemeral-owner-only", "admin"); }
    String ownerUri(String database) {
        String address = server.getHost() + ":" + server.getMappedPort(27017);
        return "mongodb://source_owner:ephemeral-owner-only@" + address + "/" + database
                + "?directConnection=true&authSource=admin";
    }
    MongoClient anonymous() { return connect(null, null, null); }

    MongoClient consumer(MongoClient owner, String database, boolean enricher) {
        String user = enricher ? "enricher" : "scorer";
        var db = owner.getDatabase(database);
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(database, "", List.of("listCollections")));
        for (String collection : List.of("source_admission_identities", "source_admission_receipts",
                "source_lineage_raw", "source_lineage_enriched")) {
            privileges.add(privilege(database, collection, List.of("find")));
        }
        for (String collection : List.of("source_lineage_raw", "source_lineage_enriched",
                "source_lineage_raw_counts", "source_lineage_enriched_counts")) {
            privileges.add(privilege(database, collection, List.of("listIndexes")));
        }
        if (enricher) {
            privileges.add(privilege(database, "source_lineage_enriched", List.of("insert", "update")));
            privileges.add(privilege(database, "source_lineage_enriched_counts", List.of("find", "insert", "update")));
            privileges.add(privilege(database, "source_enriched_publications", List.of("find", "insert", "update", "listIndexes")));
            privileges.add(privilege(database, "source_enriched_business_identities", List.of("find", "insert", "listIndexes")));
        }
        db.runCommand(new Document("createRole", user).append("privileges", privileges).append("roles", List.of()));
        db.runCommand(new Document("createUser", user).append("pwd", "ephemeral-consumer-only").append("roles", List.of(user)));
        return connect(user, "ephemeral-consumer-only", database);
    }

    private static Document privilege(String db, String collection, List<String> actions) {
        return new Document("resource", new Document("db", db).append("collection", collection)).append("actions", actions);
    }
    private MongoClient connect(String user, String password, String authDatabase) {
        String address = server.getHost() + ":" + server.getMappedPort(27017);
        String uri = "mongodb://" + (user == null ? "" : user + ":" + password + "@") + address
                + "/?directConnection=true" + (authDatabase == null ? "" : "&authSource=" + authDatabase);
        MongoClient client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
                .timeout(5, TimeUnit.SECONDS).build());
        clients.add(client);
        return client;
    }
    @Override public void close() {
        clients.forEach(MongoClient::close);
        server.stop();
    }
}
