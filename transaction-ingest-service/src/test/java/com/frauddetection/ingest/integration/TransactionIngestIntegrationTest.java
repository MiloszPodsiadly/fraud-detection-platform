package com.frauddetection.ingest.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.testsupport.container.TestContainerProperties;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.controller.TransactionIngestRequestTestData;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@EnabledIfSystemProperty(named = "runDockerIntegrationTests", matches = "true")
class TransactionIngestIntegrationTest {

    private static final String TRANSACTION_RAW_TOPIC = "transactions.raw";

    private static KafkaConsumer<String, String> consumer;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestContainerProperties.backendProperties()
                .forEach((key, value) -> registry.add(key, () -> value));
        registry.add("app.kafka.topics.transaction-raw", () -> TRANSACTION_RAW_TOPIC);
    }

    @BeforeEach
    void setUp() throws Exception {
        createTopicIfNeeded();

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestContainerProperties.backendProperties().get("spring.kafka.bootstrap-servers"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "transaction-ingest-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(TRANSACTION_RAW_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void shouldPublishRawTransactionEventAfterIngest() throws Exception {
        ResponseEntity<IngestTransactionResponse> response = testRestTemplate.exchange(
                "http://localhost:" + port + "/api/v1/transactions",
                HttpMethod.POST,
                new HttpEntity<>(TransactionIngestRequestTestData.validRequest()),
                IngestTransactionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().topic()).isEqualTo(TRANSACTION_RAW_TOPIC);

        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        ConsumerRecord<String, String> firstRecord = records.iterator().next();
        TransactionRawEvent event = objectMapper.readValue(firstRecord.value(), TransactionRawEvent.class);

        assertThat(event.transactionId()).isEqualTo("txn-1001");
        assertThat(event.customerId()).isEqualTo("cust-1001");
        assertThat(event.transactionAmount().currency()).isEqualTo("USD");
        assertThat(event.merchantInfo().merchantId()).isEqualTo("merchant-1001");
        assertThat(event.correlationId()).isNotBlank();
    }

    private void createTopicIfNeeded() {
        try (AdminClient adminClient = AdminClient.create(Map.of(
                "bootstrap.servers", TestContainerProperties.backendProperties().get("spring.kafka.bootstrap-servers")
        ))) {
            adminClient.createTopics(List.of(new NewTopic(TRANSACTION_RAW_TOPIC, 1, (short) 1))).all().get();
        } catch (Exception ignored) {
            // Topic may already exist from previous test runs.
        }
    }
}
