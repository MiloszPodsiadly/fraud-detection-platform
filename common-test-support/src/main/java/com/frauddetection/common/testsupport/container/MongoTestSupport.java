package com.frauddetection.common.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared, immutable MongoDB image and deterministic MongoDB test-container setup. */
public final class MongoTestSupport {

    public static final String MONGODB_IMAGE_REFERENCE =
            "mongo:7.0.48@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1";

    private static final DockerImageName MONGODB_IMAGE = DockerImageName.parse(MONGODB_IMAGE_REFERENCE)
            .asCompatibleSubstituteFor("mongo");
    private static final Pattern SAFE_REPLICA_SET_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_USERNAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SAFE_PASSWORD = Pattern.compile("[A-Za-z0-9._~-]{16,128}");

    private MongoTestSupport() {
    }

    public static DockerImageName dockerImageName() {
        return MONGODB_IMAGE;
    }

    public static MongoDBContainer newMongoDBContainer() {
        return new MongoDBContainer(MONGODB_IMAGE);
    }

    /**
     * Creates a test-only authenticated MongoDB server. The official image entrypoint creates the
     * bootstrap administrator before starting the secured replica-set process.
     */
    public static GenericContainer<?> newAuthenticatedMongoContainer(String replicaSetName,
            String rootUsername, String rootPassword, boolean enableTestCommands) {
        requireMatch(SAFE_REPLICA_SET_NAME, replicaSetName, "replica set name");
        requireMatch(SAFE_USERNAME, rootUsername, "bootstrap username");
        requireMatch(SAFE_PASSWORD, rootPassword, "bootstrap password");

        String keyFile = "/tmp/mongodb-test-key";
        String command = "umask 077; openssl rand -base64 756 > " + keyFile
                + "; chown mongodb:mongodb " + keyFile + "; chmod 0400 " + keyFile
                + "; exec /usr/local/bin/docker-entrypoint.sh mongod --replSet " + replicaSetName
                + " --bind_ip_all --keyFile " + keyFile
                + (enableTestCommands ? " --setParameter enableTestCommands=1" : "");

        return new GenericContainer<>(MONGODB_IMAGE)
                .withExposedPorts(27017)
                .withEnv("MONGO_INITDB_ROOT_USERNAME", rootUsername)
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", rootPassword)
                .withStartupTimeout(Duration.ofSeconds(60))
                .withCreateContainerCmdModifier(container -> container.withEntrypoint("/bin/bash"))
                .withCommand("-ec", command);
    }

    /** Starts and authenticates an ephemeral single-member replica set, safely handling repeats. */
    public static void startAuthenticatedReplicaSet(GenericContainer<?> container, String replicaSetName,
            String rootUsername, String rootPassword) throws Exception {
        Objects.requireNonNull(container, "container");
        requireMatch(SAFE_REPLICA_SET_NAME, replicaSetName, "replica set name");
        requireMatch(SAFE_USERNAME, rootUsername, "bootstrap username");
        requireMatch(SAFE_PASSWORD, rootPassword, "bootstrap password");

        String script = "const admin=db.getSiblingDB('admin');"
                + "const expected='" + replicaSetName + "';"
                + "let hello=admin.runCommand({hello:1});"
                + "if(!hello.ok) throw Error('hello failed');"
                + "if(hello.setName && hello.setName!==expected) throw Error('unexpected replica set');"
                + "if(!hello.setName){const result=admin.runCommand({replSetInitiate:{_id:expected,members:[{_id:0,host:'localhost:27017'}]}});"
                + "if(!result.ok && result.codeName!=='AlreadyInitialized') throw Error('replica set initiation failed: '+result.codeName);}"
                + "const deadline=Date.now()+30000;"
                + "while(Date.now()<deadline){hello=admin.runCommand({hello:1});"
                + "if(hello.ok && hello.setName===expected && hello.isWritablePrimary) break; sleep(100);}"
                + "if(!hello.ok || hello.setName!==expected || !hello.isWritablePrimary) throw Error('writable PRIMARY not ready');"
                + "const bootstrap=admin.getUser('" + rootUsername + "');"
                + "if(!bootstrap || !bootstrap.roles.some(role=>role.role==='root' && role.db==='admin'))"
                + " throw Error('bootstrap administrator role missing');";
        String firstUserScript = "const admin=db.getSiblingDB('admin');"
                + "const expected='" + replicaSetName + "';"
                + "let hello=admin.runCommand({hello:1});"
                + "if(!hello.ok) throw Error('hello failed');"
                + "if(hello.setName && hello.setName!==expected) throw Error('unexpected replica set');"
                + "if(!hello.setName){const result=admin.runCommand({replSetInitiate:{_id:expected,members:[{_id:0,host:'localhost:27017'}]}});"
                + "if(!result.ok && result.codeName!=='AlreadyInitialized') throw Error('replica set initiation failed: '+result.codeName);}"
                + "const deadline=Date.now()+30000;"
                + "while(Date.now()<deadline){hello=admin.runCommand({hello:1});"
                + "if(hello.ok && hello.setName===expected && hello.isWritablePrimary) break; sleep(100);}"
                + "if(!hello.ok || hello.setName!==expected || !hello.isWritablePrimary) throw Error('writable PRIMARY not ready');"
                + "admin.createUser({user:'" + rootUsername + "',pwd:'" + rootPassword
                + "',roles:[{role:'root',db:'admin'}]});";

        try {
            if (!container.isRunning()) {
                container.start();
            }
            String topologyScript = "const hello=db.adminCommand({hello:1});"
                    + "if(hello.setName && hello.setName!=='" + replicaSetName
                    + "') throw Error('unexpected replica set');";
            var topology = container.execInContainer("mongosh", "--quiet", "--host", "127.0.0.1",
                    "--eval", topologyScript);
            String topologyDiagnostics = topology.getStderr() + " " + topology.getStdout();
            if (topology.getExitCode() != 0 && topologyDiagnostics.contains("unexpected replica set")) {
                throw new IllegalStateException("MongoDB replica set does not match expected '" + replicaSetName + "'");
            }
            var result = executeAuthenticatedUntilReady(container, rootUsername, rootPassword, script);
            if (result.getExitCode() != 0) {
                // MongoDB's localhost exception only permits creating the first user while no users or roles exist.
                var localBootstrap = container.execInContainer("mongosh", "--quiet", "--host", "127.0.0.1",
                        "--eval", firstUserScript);
                if (localBootstrap.getExitCode() == 0) {
                    result = executeAuthenticated(container, rootUsername, rootPassword, script);
                } else {
                    String localDiagnostics = (localBootstrap.getStderr() + " " + localBootstrap.getStdout())
                            .replace(rootPassword, "[redacted]");
                    throw new IllegalStateException("MongoDB localhost first-user bootstrap failed: "
                            + localDiagnostics.trim());
                }
            }
            if (result.getExitCode() != 0) {
                String logs;
                try {
                    logs = container.getLogs();
                } catch (RuntimeException logFailure) {
                    logs = "MongoDB container logs unavailable";
                }
                if (logs.length() > 6000) {
                    logs = logs.substring(logs.length() - 6000);
                }
                String diagnostics = (result.getStderr() + " " + result.getStdout() + " " + logs)
                        .replace(rootPassword, "[redacted]");
                throw new IllegalStateException("Authenticated MongoDB test replica-set bootstrap failed: "
                        + diagnostics.trim() + " Startup configuration: " + startupConfiguration(container, rootUsername));
            }
        } catch (Exception failure) {
            try {
                container.stop();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure;
        }
    }

    private static org.testcontainers.containers.Container.ExecResult executeAuthenticated(
            GenericContainer<?> container, String rootUsername, String rootPassword, String script) throws Exception {
        return container.execInContainer("mongosh", "--quiet", "--host", "127.0.0.1",
                "--username", rootUsername, "--password", rootPassword,
                "--authenticationDatabase", "admin", "--eval", script);
    }

    private static org.testcontainers.containers.Container.ExecResult executeAuthenticatedUntilReady(
            GenericContainer<?> container, String rootUsername, String rootPassword, String script) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        org.testcontainers.containers.Container.ExecResult result;
        do {
            result = executeAuthenticated(container, rootUsername, rootPassword, script);
            if (result.getExitCode() == 0) {
                return result;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        return result;
    }

    private static void requireMatch(Pattern pattern, String value, String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe MongoDB test " + label);
        }
    }

    private static String startupConfiguration(GenericContainer<?> container, String rootUsername) {
        try {
            var config = container.getContainerInfo().getConfig();
            boolean rootUsernameConfigured = java.util.Arrays.stream(config.getEnv())
                    .anyMatch(value -> value.equals("MONGO_INITDB_ROOT_USERNAME=" + rootUsername));
            return "entrypoint=" + java.util.Arrays.toString(config.getEntrypoint())
                    + ", command=" + java.util.Arrays.toString(config.getCmd())
                    + ", bootstrap username configured=" + rootUsernameConfigured;
        } catch (RuntimeException inspectionFailure) {
            return "container configuration unavailable";
        }
    }
}
