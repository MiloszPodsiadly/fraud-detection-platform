package com.frauddetection.common.testsupport.container;

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
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaCompressionIntegrationTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeCompressionAndBrokerRecompressionWorkForLz4AndSnappy() throws Exception {
        try (KafkaContainer kafka = new KafkaContainer(KafkaImageReference.dockerImageName())
                .withImagePullPolicy(KafkaImageReference.localOnlyPullPolicy())) {
            kafka.start();
            assertNativeLibrariesLoadInsideAlpine(kafka);

            assertTransactionalCompressionRoundTrip(kafka, "lz4", "snappy");
            assertTransactionalCompressionRoundTrip(kafka, "snappy", "lz4");
        }
    }

    private static void assertNativeLibrariesLoadInsideAlpine(KafkaContainer kafka) throws Exception {
        String classResource = "com/frauddetection/common/testsupport/container/KafkaNativeCompressionProbe.class";
        var directory = kafka.execInContainer("mkdir", "-p", "/tmp/com/frauddetection/common/testsupport/container");
        assertThat(directory.getExitCode()).as(directory.getStderr()).isZero();
        kafka.copyFileToContainer(MountableFile.forClasspathResource(classResource),
                "/tmp/" + classResource);
        var result = kafka.execInContainer("java", "-cp", "/tmp:/opt/kafka/libs/*",
                KafkaNativeCompressionProbe.class.getName());

        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("lz4-native=", "snappy-native=");
    }

    private static void assertTransactionalCompressionRoundTrip(KafkaContainer kafka,
                                                                 String producerCodec,
                                                                 String brokerCodec) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = "fdp157-compression-" + producerCodec + "-" + suffix;
        String group = "fdp157-compression-group-" + suffix;
        createTopic(kafka, topic, brokerCodec);
        produceTransactionalRecords(kafka, topic, producerCodec, suffix);

        assertThat(consumeCommittedRecords(kafka, topic, group))
                .containsExactlyInAnyOrder("alpha=value-one", "beta=value-two")
                .doesNotContain("aborted=must-not-appear");
    }

    private static void createTopic(KafkaContainer kafka, String topic, String brokerCodec) throws Exception {
        NewTopic newTopic = new NewTopic(topic, 1, (short) 1)
                .configs(Map.of(TopicConfig.COMPRESSION_TYPE_CONFIG, brokerCodec));
        try (Admin admin = AdminClient.create(adminProperties(kafka))) {
            admin.createTopics(List.of(newTopic)).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static void produceTransactionalRecords(KafkaContainer kafka,
                                                    String topic,
                                                    String codec,
                                                    String suffix) throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, codec);
        properties.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "fdp157-compression-" + suffix);
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
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

    private static List<String> consumeCommittedRecords(KafkaContainer kafka,
                                                         String topic,
                                                         String group) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        List<String> recordsRead = new ArrayList<>();
        try (Consumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (recordsRead.size() < 2 && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                records.forEach(record -> recordsRead.add(record.key() + "=" + record.value()));
            }
            consumer.commitSync(Duration.ofSeconds(10));
        }
        return recordsRead;
    }

    private static Properties adminProperties(KafkaContainer kafka) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", kafka.getBootstrapServers());
        return properties;
    }
}
