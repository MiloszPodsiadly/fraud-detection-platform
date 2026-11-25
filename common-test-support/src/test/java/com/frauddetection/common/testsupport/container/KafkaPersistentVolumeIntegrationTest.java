package com.frauddetection.common.testsupport.container;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPersistentVolumeIntegrationTest {

    private static final String IMAGE = KafkaImageReference.IMAGE;
    private static final String DATA_DIRECTORY = "/var/lib/kafka/data";
    private static final String CLUSTER_ID = "4L6g3nShT-eMCtK--X86sw";
    private static final Pattern COMPOSE_VALUE = Pattern.compile("(?m)^\\s+%s:\\s*([^\\s#]+)\\s*$");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void persistentKraftVolumeRetainsIdentityTransactionsTopicsMessagesAndOffsetsAcrossRecreation() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String volumeName = createTestVolume(suffix);
        String topic = "fdp157-persist-" + suffix;
        String consumerGroup = "fdp157-persist-group-" + suffix;
        Network network = null;
        try {
            String compose = Files.readString(composeFile());
            assertThat(compose)
                    .contains("CLUSTER_ID: " + CLUSTER_ID)
                    .contains("KAFKA_LOG_DIRS: " + DATA_DIRECTORY)
                    .contains("- kafka-data:" + DATA_DIRECTORY);

            network = Network.newNetwork();
            Map<TopicPartition, Long> committedOffsets;
            try (KafkaContainer initial = newBroker(network, volumeName)) {
                initial.start();
                assertBrokerIdentityAndDataOwnership(initial, topic, true);
                createTopicAndCommitTransaction(initial, topic, suffix);
                consumeAndCommit(initial, topic, consumerGroup);
                committedOffsets = committedOffsets(initial, consumerGroup);
                assertThat(committedOffsets).isNotEmpty();
                assertTopicState(initial, topic, 2, true);
                initial.stop();
            }

            try (KafkaContainer recreated = newBroker(network, volumeName)) {
                recreated.start();
                assertBrokerIdentityAndDataOwnership(recreated, topic, true);
                assertTopicState(recreated, topic, 2, true);
                assertThat(committedOffsets(recreated, consumerGroup)).isEqualTo(committedOffsets);
                assertThat(consumeAndCommit(recreated, topic, "fdp157-replay-" + suffix))
                        .containsExactlyInAnyOrder("alpha=value-one", "beta=value-two");
            }
        } finally {
            try {
                if (network != null) network.close();
            } finally {
                removeTestVolume(volumeName);
            }
        }
    }

    private static KafkaContainer newBroker(Network network, String volumeName) throws Exception {
        String clusterId = composeValue("CLUSTER_ID");
        String logDirectory = composeValue("KAFKA_LOG_DIRS");
        assertThat(clusterId).isEqualTo(CLUSTER_ID);
        assertThat(logDirectory).isEqualTo(DATA_DIRECTORY);
        return new KafkaContainer(KafkaImageReference.dockerImageName())
                .withImagePullPolicy(KafkaImageReference.localOnlyPullPolicy())
                .withNetwork(network)
                .withNetworkAliases("kafka")
                .withListener("kafka:19092")
                .withEnv("CLUSTER_ID", clusterId)
                .withEnv("KAFKA_LOG_DIRS", logDirectory)
                .withCreateContainerCmdModifier(command -> addNamedVolume(command, volumeName, logDirectory));
    }

    private static void createTopicAndCommitTransaction(KafkaContainer kafka, String topic, String suffix) throws Exception {
        try (Admin admin = AdminClient.create(adminProperties(kafka))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "fdp157-transaction-" + suffix);
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
            producer.initTransactions();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(topic, "alpha", "value-one")).get(30, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, "beta", "value-two")).get(30, TimeUnit.SECONDS);
            producer.commitTransaction();

            producer.beginTransaction();
            producer.send(new ProducerRecord<>(topic, "aborted", "must-not-appear")).get(30, TimeUnit.SECONDS);
            producer.abortTransaction();
        }
    }

    private static List<String> consumeAndCommit(KafkaContainer kafka, String topic, String groupId) throws Exception {
        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProperties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        List<String> recordsRead = new ArrayList<>();
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (recordsRead.size() < 2 && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                records.forEach(record -> recordsRead.add(record.key() + "=" + record.value()));
            }
            consumer.commitSync(Duration.ofSeconds(10));
        }
        assertThat(recordsRead).hasSize(2).doesNotContain("aborted=must-not-appear");
        return recordsRead;
    }

    private static Map<TopicPartition, Long> committedOffsets(KafkaContainer kafka, String groupId) throws Exception {
        try (Admin admin = AdminClient.create(adminProperties(kafka))) {
            Map<TopicPartition, Long> offsets = new HashMap<>();
            admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get(30, TimeUnit.SECONDS)
                    .forEach((partition, offset) -> offsets.put(partition, offset.offset()));
            return offsets;
        }
    }

    private static void assertTopicState(KafkaContainer kafka, String topic, int expectedPartitions,
                                         boolean expectTransactionStateTopic) throws Exception {
        try (Admin admin = AdminClient.create(adminProperties(kafka))) {
            assertThat(admin.describeCluster().clusterId().get(30, TimeUnit.SECONDS)).isEqualTo(CLUSTER_ID);
            var description = admin.describeTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS).get(topic);
            assertThat(description.partitions()).hasSize(expectedPartitions);
            var topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).contains(topic);
            if (expectTransactionStateTopic) assertThat(topics).contains("__transaction_state");
        }
    }

    private static void assertBrokerIdentityAndDataOwnership(KafkaContainer kafka, String topic,
                                                             boolean expectTransactionStateTopic) throws Exception {
        var version = kafka.execInContainer("/opt/kafka/bin/kafka-topics.sh", "--version");
        assertThat(version.getExitCode()).isZero();
        assertThat(version.getStdout()).contains("4.4.0");

        var ownership = kafka.execInContainer("bash", "-lc",
                "test -f " + DATA_DIRECTORY + "/meta.properties"
                        + " && test -w " + DATA_DIRECTORY
                        + " && printf '%s\\n' \"$(id -u)\" \"$(stat -c '%u' " + DATA_DIRECTORY + ")\"");
        assertThat(ownership.getExitCode()).as(ownership.getStderr()).isZero();
        List<String> ownerIds = ownership.getStdout().lines().toList();
        assertThat(ownerIds).hasSize(2).doesNotContain("0");
        assertThat(ownerIds.get(1)).isEqualTo(ownerIds.get(0));
        assertTopicState(kafka, topic, 2, expectTransactionStateTopic);
    }

    private static Properties adminProperties(KafkaContainer kafka) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", kafka.getBootstrapServers());
        properties.put("request.timeout.ms", "10000");
        properties.put("default.api.timeout.ms", "15000");
        return properties;
    }

    private static void addNamedVolume(CreateContainerCmd command, String volumeName, String dataDirectory) {
        HostConfig hostConfig = command.getHostConfig();
        if (hostConfig == null) {
            hostConfig = new HostConfig();
            command.withHostConfig(hostConfig);
        }
        Bind[] configuredBinds = hostConfig.getBinds();
        List<Bind> binds = configuredBinds == null
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(configuredBinds));
        binds.add(Bind.parse(volumeName + ":" + dataDirectory + ":rw"));
        hostConfig.withBinds(binds);
    }

    private static String createTestVolume(String suffix) throws Exception {
        String name = "fdp157-kafka-volume-" + suffix;
        DockerResult result = docker("volume", "create", name);
        if (result.exitCode() != 0 || !result.output().trim().equals(name)) {
            throw new IllegalStateException("Could not create isolated Kafka persistence test volume: " + result.output());
        }
        return name;
    }

    private static void removeTestVolume(String name) throws Exception {
        if (name == null || !name.matches("fdp157-kafka-volume-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Refusing to remove a volume not created by this Kafka persistence test");
        }
        DockerResult result = docker("volume", "rm", name);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Could not remove isolated Kafka persistence test volume: " + result.output());
        }
    }

    private static DockerResult docker(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new DockerResult(process.waitFor(), output);
    }

    private static String composeValue(String key) throws IOException {
        Matcher matcher = Pattern.compile(String.format(COMPOSE_VALUE.pattern(), Pattern.quote(key)))
                .matcher(Files.readString(composeFile()));
        if (!matcher.find()) throw new IllegalStateException("Missing " + key + " in deployment/docker-compose.yml");
        return matcher.group(1);
    }

    private static Path composeFile() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("deployment/docker-compose.yml");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate deployment/docker-compose.yml from the test working directory");
    }

    private record DockerResult(int exitCode, String output) {
    }
}
