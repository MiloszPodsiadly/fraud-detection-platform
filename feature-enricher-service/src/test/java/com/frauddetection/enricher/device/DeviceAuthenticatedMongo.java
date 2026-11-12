package com.frauddetection.enricher.device;

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
final class DeviceAuthenticatedMongo implements AutoCloseable {
    private final GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
            .withExposedPorts(27017).withStartupTimeout(Duration.ofSeconds(60))
            .withCommand("bash", "-c", "openssl rand -base64 756 > /tmp/lineage-key; chmod 400 /tmp/lineage-key; "
                    + "exec mongod --replSet lineage-rs --bind_ip_all --keyFile /tmp/lineage-key --setParameter enableTestCommands=1");
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
    MongoClient ledgerWriter(MongoClient owner, String database) {
        var db = owner.getDatabase(database);
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(database, "", List.of("listCollections")));
        for (String collection : MongoDeviceObservationSchema.COLLECTIONS) {
            List<String> actions = new ArrayList<>(List.of("find", "listIndexes", "insert", "update"));
            if (collection.equals("device_observations")) { actions.add("remove"); }
            privileges.add(privilege(database, collection, actions));
        }
        db.runCommand(new Document("createRole", "device_ledger").append("privileges", privileges).append("roles", List.of()));
        db.runCommand(new Document("createUser", "device_ledger").append("pwd", "ephemeral-device-only").append("roles", List.of("device_ledger")));
        return connect("device_ledger", "ephemeral-device-only", database, "device-ledger-test");
    }
    MongoClient anonymous() { return connect(null, null, null); }

    MongoClient consumer(MongoClient owner, String database, boolean enricher) {
        return consumer(owner, database, enricher, false);
    }
    MongoClient occurrenceWriter(MongoClient owner, String database) { return consumer(owner, database, false, true); }
    private MongoClient consumer(MongoClient owner, String database, boolean enricher, boolean occurrenceWriter) {
        String user = enricher ? "enricher" : occurrenceWriter ? "scored_writer" : "scorer";
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
            privileges.add(privilege(database, "source_feature_order_gates", List.of("find", "update", "listIndexes")));
            privileges.add(privilege(database, "source_raw_terminal_dispositions", List.of("find", "insert", "listIndexes")));
        }
        if (occurrenceWriter) {
            for (String collection : List.of("source_scored_occurrences", "source_scored_occurrence_counts")) {
                privileges.add(privilege(database, collection, List.of("find", "insert", "update", "listIndexes")));
            }
        }
        db.runCommand(new Document("createRole", user).append("privileges", privileges).append("roles", List.of()));
        db.runCommand(new Document("createUser", user).append("pwd", "ephemeral-consumer-only").append("roles", List.of(user)));
        return connect(user, "ephemeral-consumer-only", database);
    }

    MongoClient occurrenceReader(MongoClient owner, String database) {
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(database, "", List.of("listCollections")));
        for (String collection : List.of("source_admission_identities", "source_admission_receipts", "source_lineage_raw", "source_lineage_enriched")) {
            privileges.add(privilege(database, collection, List.of("find")));
        }
        for (String collection : List.of("source_lineage_raw", "source_lineage_enriched", "source_lineage_raw_counts", "source_lineage_enriched_counts",
                "source_scored_occurrences", "source_scored_occurrence_counts")) {
            privileges.add(privilege(database, collection, collection.equals("source_scored_occurrences") ? List.of("find", "listIndexes") : List.of("listIndexes")));
        }
        return readPrincipal(owner, database, "source_diagnostic_reader", privileges);
    }

    MongoClient diagnosticReader(MongoClient owner, String database) {
        List<Document> privileges = new ArrayList<>();
        privileges.add(privilege(database, "", List.of("listCollections")));
        for (String collection : PrivateDeviceDiagnosticReader.COLLECTIONS) {
            privileges.add(privilege(database, collection, List.of("find", "listIndexes")));
        }
        return readPrincipal(owner, database, "device_reader", privileges);
    }

    private MongoClient readPrincipal(MongoClient owner, String database, String user, List<Document> privileges) {
        var db = owner.getDatabase(database);
        db.runCommand(new Document("createRole", user).append("privileges", privileges).append("roles", List.of()));
        db.runCommand(new Document("createUser", user).append("pwd", "ephemeral-reader-only").append("roles", List.of(user)));
        return connect(user, "ephemeral-reader-only", database);
    }

    private static Document privilege(String db, String collection, List<String> actions) {
        return new Document("resource", new Document("db", db).append("collection", collection)).append("actions", actions);
    }
    private MongoClient connect(String user, String password, String authDatabase) {
        return connect(user, password, authDatabase, null);
    }
    private MongoClient connect(String user, String password, String authDatabase, String applicationName) {
        String address = server.getHost() + ":" + server.getMappedPort(27017);
        String uri = "mongodb://" + (user == null ? "" : user + ":" + password + "@") + address
                + "/?directConnection=true" + (authDatabase == null ? "" : "&authSource=" + authDatabase);
        MongoClient client = MongoClients.create(MongoClientSettings.builder().applicationName(applicationName).applyConnectionString(new ConnectionString(uri))
                .timeout(5, TimeUnit.SECONDS).build());
        clients.add(client);
        return client;
    }
    MongoClient reconnectScorer(String database) { return connect("scorer", "ephemeral-consumer-only", database); }
    String scorerUri(String database) {
        return "mongodb://scorer:ephemeral-consumer-only@" + server.getHost() + ":" + server.getMappedPort(27017)
                + "/?directConnection=true&authSource=" + database;
    }
    Document resourceLimits() {
        var host = server.getContainerInfo().getHostConfig();
        return new Document("image", server.getDockerImageName()).append("memoryBytes", host.getMemory()).append("nanoCpus", host.getNanoCPUs());
    }
    @Override public void close() {
        clients.forEach(MongoClient::close);
        server.stop();
    }
}
