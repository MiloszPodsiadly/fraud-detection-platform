package com.frauddetection.alert.integration;

import com.frauddetection.alert.AlertServiceApplication;
import com.frauddetection.alert.api.AlertDetailsResponse;
import com.frauddetection.alert.api.AlertSummaryResponse;
import com.frauddetection.alert.api.PagedResponse;
import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseRepository;
import com.frauddetection.alert.security.auth.DemoAuthHeaders;
import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.features.FraudFeatureThresholdContract;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.enricher.FeatureEnricherServiceApplication;
import com.frauddetection.ingest.TransactionIngestServiceApplication;
import com.frauddetection.ingest.api.CustomerContextRequest;
import com.frauddetection.ingest.api.DeviceInfoRequest;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.api.IngestTransactionResponse;
import com.frauddetection.ingest.api.LocationInfoRequest;
import com.frauddetection.ingest.api.MerchantInfoRequest;
import com.frauddetection.ingest.api.MoneyRequest;
import com.frauddetection.scoring.FraudScoringServiceApplication;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("dockerAvailable")
class FraudDetectionPlatformEndToEndIntegrationTest {

    private static final String DISABLE_DEFAULT_SECURITY_AUTO_CONFIG =
            "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration";
    private static final String TOPIC_SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String E2E_ANALYST_USER = "e2e-analyst";
    private static final String E2E_ANALYST_ROLE = "FRAUD_OPS_ADMIN";
    private static final String TRANSACTION_RAW_TOPIC = "transactions.raw.e2e." + TOPIC_SUFFIX;
    private static final String TRANSACTION_ENRICHED_TOPIC = "transactions.enriched.e2e." + TOPIC_SUFFIX;
    private static final String TRANSACTION_SCORED_TOPIC = "transactions.scored.e2e." + TOPIC_SUFFIX;
    private static final String FRAUD_ALERTS_TOPIC = "fraud.alerts.e2e." + TOPIC_SUFFIX;
    private static final String FRAUD_DECISIONS_TOPIC = "fraud.decisions.e2e." + TOPIC_SUFFIX;
    private static final String DEAD_LETTER_TOPIC = "transactions.dead-letter.e2e." + TOPIC_SUFFIX;
    private static final String MONGODB_DATABASE = "fraud_platform_e2e_" + TOPIC_SUFFIX;
    private static final int PLATFORM_TOPIC_PARTITIONS = 3;
    private static final short PLATFORM_TOPIC_REPLICAS = 1;
    private static final Duration E2E_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private final RestTemplate restTemplate = new RestTemplate();

    private ConfigurableApplicationContext transactionIngestContext;
    private ConfigurableApplicationContext featureEnricherContext;
    private ConfigurableApplicationContext fraudScoringContext;
    private ConfigurableApplicationContext alertContext;

    @BeforeAll
    void startPlatform() {
        FraudPlatformContainers.startAll();
        createKafkaTopics();

        transactionIngestContext = startWebApplication(TransactionIngestServiceApplication.class, Map.of(
                "server.port", 0,
                "spring.application.name", "transaction-ingest-service-e2e-" + TOPIC_SUFFIX,
                "spring.autoconfigure.exclude", DISABLE_DEFAULT_SECURITY_AUTO_CONFIG,
                "spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers(),
                "app.kafka.topics.transaction-raw", TRANSACTION_RAW_TOPIC
        ));

        featureEnricherContext = startWorkerApplication(FeatureEnricherServiceApplication.class, Map.ofEntries(
                Map.entry("spring.application.name", "feature-enricher-service-e2e-" + TOPIC_SUFFIX),
                Map.entry("spring.autoconfigure.exclude", DISABLE_DEFAULT_SECURITY_AUTO_CONFIG),
                Map.entry("spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers()),
                Map.entry("spring.kafka.consumer.group-id", "feature-enricher-e2e-" + TOPIC_SUFFIX),
                Map.entry("spring.data.redis.host", FraudPlatformContainers.redis().getHost()),
                Map.entry("spring.data.redis.port", FraudPlatformContainers.redis().getMappedPort(6379)),
                Map.entry("app.kafka.topics.transaction-raw", TRANSACTION_RAW_TOPIC),
                Map.entry("app.kafka.topics.transaction-enriched", TRANSACTION_ENRICHED_TOPIC),
                Map.entry("app.kafka.topics.transactions-dead-letter", DEAD_LETTER_TOPIC),
                Map.entry("app.feature-store.recent-transaction-window", "PT1M"),
                Map.entry("app.feature-store.merchant-frequency-window", "P7D"),
                Map.entry("app.feature-store.transaction-key-ttl", "P8D"),
                Map.entry("app.feature-store.known-device-ttl", "P180D"),
                Map.entry("app.feature-store.last-transaction-ttl", "P30D")
        ));

        fraudScoringContext = startWorkerApplication(FraudScoringServiceApplication.class, Map.of(
                "spring.application.name", "fraud-scoring-service-e2e-" + TOPIC_SUFFIX,
                "spring.autoconfigure.exclude", DISABLE_DEFAULT_SECURITY_AUTO_CONFIG,
                "spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers(),
                "spring.kafka.consumer.group-id", "fraud-scoring-e2e-" + TOPIC_SUFFIX,
                "app.kafka.topics.transaction-enriched", TRANSACTION_ENRICHED_TOPIC,
                "app.kafka.topics.transaction-scored", TRANSACTION_SCORED_TOPIC,
                "app.kafka.topics.transactions-dead-letter", DEAD_LETTER_TOPIC,
                "app.scoring.high-threshold", "0.75",
                "app.scoring.critical-threshold", "0.90",
                "app.scoring.mode", "RULE_BASED"
        ));

        alertContext = startWebApplication(AlertServiceApplication.class, Map.ofEntries(
                Map.entry("server.port", 0),
                Map.entry("spring.application.name", "alert-service-e2e-" + TOPIC_SUFFIX),
                Map.entry("spring.profiles.active", "test"),
                Map.entry("spring.kafka.bootstrap-servers", FraudPlatformContainers.kafka().getBootstrapServers()),
                Map.entry("spring.kafka.consumer.group-id", "alert-e2e-" + TOPIC_SUFFIX),
                Map.entry("spring.mongodb.uri", FraudPlatformContainers.mongodb().getReplicaSetUrl(MONGODB_DATABASE)),
                Map.entry("app.security.demo-auth.enabled", true),
                Map.entry("app.kafka.topics.transaction-scored", TRANSACTION_SCORED_TOPIC),
                Map.entry("app.kafka.topics.fraud-alerts", FRAUD_ALERTS_TOPIC),
                Map.entry("app.kafka.topics.fraud-decisions", FRAUD_DECISIONS_TOPIC),
                Map.entry("app.kafka.topics.transactions-dead-letter", DEAD_LETTER_TOPIC)
        ));
    }

    @AfterAll
    void stopPlatform() {
        closeContext(alertContext);
        closeContext(fraudScoringContext);
        closeContext(featureEnricherContext);
        closeContext(transactionIngestContext);
    }

    @Test
    void shouldProcessHighRiskTransactionAcrossTheFullPlatformFlow() {
        String seedTransactionId = "txn-e2e-seed-" + TOPIC_SUFFIX;
        String transactionId = "txn-e2e-" + TOPIC_SUFFIX;
        String customerId = "cust-e2e-" + TOPIC_SUFFIX;
        String accountId = "acct-e2e-" + TOPIC_SUFFIX;
        String paymentInstrumentId = "card-e2e-" + TOPIC_SUFFIX;
        String seedDeviceId = "device-e2e-seed-" + TOPIC_SUFFIX;
        String deviceId = "device-e2e-" + TOPIC_SUFFIX;
        String correlationId = "corr-e2e-" + TOPIC_SUFFIX;

        IngestTransactionRequest seedRequest = buildHighRiskRequest(
                seedTransactionId,
                customerId,
                accountId,
                paymentInstrumentId,
                seedDeviceId,
                Instant.now().minusSeconds(45),
                new BigDecimal("10000.00"),
                "PLN"
        );
        ResponseEntity<IngestTransactionResponse> seedResponse = submitTransaction(seedRequest, correlationId + "-seed");
        assertThat(seedResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitKafkaRecord(TRANSACTION_ENRICHED_TOPIC, TransactionEnrichedEvent.class, seedTransactionId);

        IngestTransactionRequest request = buildHighRiskRequest(
                transactionId,
                customerId,
                accountId,
                paymentInstrumentId,
                deviceId,
                Instant.now().minusSeconds(15),
                new BigDecimal("10000.00"),
                "PLN"
        );

        ResponseEntity<IngestTransactionResponse> response = submitTransaction(request, correlationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().transactionId()).isEqualTo(transactionId);
        assertThat(response.getBody().topic()).isEqualTo(TRANSACTION_RAW_TOPIC);
        assertThat(response.getBody().correlationId()).isEqualTo(correlationId);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);

        ConsumerRecord<String, TransactionRawEvent> rawRecord = awaitKafkaRecord(
                TRANSACTION_RAW_TOPIC,
                TransactionRawEvent.class,
                transactionId
        );
        assertThat(rawRecord.value().transactionId()).isEqualTo(transactionId);
        assertThat(rawRecord.value().correlationId()).isEqualTo(correlationId);
        assertThat(rawRecord.value().locationInfo().countryCode()).isEqualTo("GB");

        ConsumerRecord<String, TransactionEnrichedEvent> enrichedRecord = awaitKafkaRecord(
                TRANSACTION_ENRICHED_TOPIC,
                TransactionEnrichedEvent.class,
                transactionId
        );
        assertThat(enrichedRecord.value().transactionId()).isEqualTo(transactionId);
        assertThat(enrichedRecord.value().correlationId()).isEqualTo(correlationId);
        assertThat(enrichedRecord.value().featureSnapshot()).containsEntry(FraudFeatureContract.DEVICE_NOVELTY, true)
                .containsEntry(FraudFeatureContract.COUNTRY_MISMATCH, true)
                .containsEntry(FraudFeatureContract.PROXY_OR_VPN_DETECTED, true)
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2)
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M")
                .containsEntry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
                .containsEntry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M")
                .containsEntry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("10000.00"))
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of(seedTransactionId, transactionId));
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(enrichedRecord.value().featureSnapshot())).isTrue();

        ConsumerRecord<String, TransactionScoredEvent> scoredRecord = awaitKafkaRecord(
                TRANSACTION_SCORED_TOPIC,
                TransactionScoredEvent.class,
                transactionId
        );
        assertThat(scoredRecord.value().transactionId()).isEqualTo(transactionId);
        assertThat(scoredRecord.value().correlationId()).isEqualTo(correlationId);
        assertThat(scoredRecord.value().riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(scoredRecord.value().fraudScore()).isGreaterThanOrEqualTo(0.90d);
        assertThat(scoredRecord.value().alertRecommended()).isTrue();
        assertThat(scoredRecord.value().reasonCodes()).contains(
                "DEVICE_NOVELTY",
                "COUNTRY_MISMATCH",
                "PROXY_OR_VPN",
                "HIGH_AMOUNT_ACTIVITY",
                "RAPID_PLN_20K_BURST",
                "HIGH_TRANSACTION_AMOUNT"
        );
        assertThat(scoredRecord.value().featureSnapshot())
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2)
                .containsEntry(FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M")
                .containsEntry(FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"))
                .containsEntry(FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M")
                .containsEntry(FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("10000.00"))
                .containsEntry(FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS, List.of(seedTransactionId, transactionId));
        assertThat(FraudFeatureThresholdContract.isRapidTransferPlnBurst(scoredRecord.value().featureSnapshot())).isTrue();

        AlertDocument persistedAlert = awaitCondition(() -> alertRepository().findAll().stream()
                .filter(alert -> transactionId.equals(alert.getTransactionId()))
                .findFirst());

        assertThat(persistedAlert.getTransactionId()).isEqualTo(transactionId);
        assertThat(persistedAlert.getCorrelationId()).isEqualTo(correlationId);
        assertThat(persistedAlert.getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(persistedAlert.getAlertStatus()).isEqualTo(AlertStatus.OPEN);

        FraudCaseDocument fraudCase = awaitCondition(() -> fraudCaseRepository().findAll().stream()
                .filter(caseDocument -> caseDocument.getTransactionIds() != null
                        && caseDocument.getTransactionIds().contains(transactionId))
                .findFirst());
        assertThat(fraudCase.getStatus()).isEqualTo(FraudCaseStatus.OPEN);
        assertThat(fraudCase.getTransactionIds()).contains(seedTransactionId, transactionId);
        assertThat(fraudCase.getTotalAmountPln()).isEqualByComparingTo("20000.00");
        assertThat(fraudCase.getThresholdPln()).isEqualByComparingTo(FraudFeatureThresholdContract.RAPID_TRANSFER_PLN_THRESHOLD);
        assertThat(fraudCase.getAggregationWindow()).isEqualTo("PT1M");

        AlertSummaryResponse summary = awaitCondition(() -> findAlertSummary(transactionId));
        assertThat(summary.transactionId()).isEqualTo(transactionId);
        assertThat(summary.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(summary.alertStatus()).isEqualTo(AlertStatus.OPEN);

        AlertDetailsResponse details = restTemplate.exchange(
                alertUrl("/api/v1/alerts/" + summary.alertId()),
                HttpMethod.GET,
                analystApiRequest(),
                AlertDetailsResponse.class
        ).getBody();

        assertThat(details).isNotNull();
        assertThat(details.transactionId()).isEqualTo(transactionId);
        assertThat(details.correlationId()).isEqualTo(correlationId);
        assertThat(details.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(details.alertStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(details.reasonCodes()).contains(
                "DEVICE_NOVELTY",
                "COUNTRY_MISMATCH",
                "PROXY_OR_VPN",
                "RAPID_PLN_20K_BURST",
                "HIGH_TRANSACTION_AMOUNT"
        );
        assertThat(details.featureSnapshot()).containsEntry("countryMismatch", true);
        assertThat(details.featureSnapshot()).containsEntry("proxyOrVpnDetected", true);
        assertThat(details.featureSnapshot()).containsEntry("deviceNovelty", true);
    }

    static boolean dockerAvailable() {
        return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    }

    private ConfigurableApplicationContext startWebApplication(Class<?> applicationClass, Map<String, Object> properties) {
        return new SpringApplicationBuilder(applicationClass)
                .run(commandLineArgs(properties));
    }

    private ConfigurableApplicationContext startWorkerApplication(Class<?> applicationClass, Map<String, Object> properties) {
        return new SpringApplicationBuilder(applicationClass)
                .run(commandLineArgs(properties, "spring.main.web-application-type=none"));
    }

    private String[] commandLineArgs(Map<String, Object> properties, String... additionalProperties) {
        List<String> args = new ArrayList<>();
        properties.forEach((name, value) -> args.add("--" + name + "=" + value));
        Arrays.stream(additionalProperties)
                .map(property -> "--" + property)
                .forEach(args::add);
        return args.toArray(String[]::new);
    }

    private void closeContext(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }

    private AlertRepository alertRepository() {
        return alertContext.getBean(AlertRepository.class);
    }

    private FraudCaseRepository fraudCaseRepository() {
        return alertContext.getBean(FraudCaseRepository.class);
    }

    private String ingestUrl(String path) {
        return "http://localhost:" + localPort(transactionIngestContext) + path;
    }

    private String alertUrl(String path) {
        return "http://localhost:" + localPort(alertContext) + path;
    }

    private int localPort(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    private void createKafkaTopics() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers()
        );

        try (AdminClient adminClient = AdminClient.create(properties)) {
            adminClient.createTopics(List.of(
                    new NewTopic(TRANSACTION_RAW_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                    new NewTopic(TRANSACTION_ENRICHED_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                    new NewTopic(TRANSACTION_SCORED_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                    new NewTopic(FRAUD_ALERTS_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                    new NewTopic(FRAUD_DECISIONS_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                    new NewTopic(DEAD_LETTER_TOPIC, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS)
            )).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (!(exception.getCause() instanceof TopicExistsException)) {
                throw new AssertionError("E2E Kafka topics could not be created.", exception);
            }
        } catch (Exception exception) {
            throw new AssertionError("E2E Kafka topics could not be created.", exception);
        }
    }

    private IngestTransactionRequest buildHighRiskRequest(
            String transactionId,
            String customerId,
            String accountId,
            String paymentInstrumentId,
            String deviceId,
            Instant transactionTimestamp,
            BigDecimal amount,
            String currency
    ) {
        return new IngestTransactionRequest(
                transactionId,
                customerId,
                accountId,
                paymentInstrumentId,
                transactionTimestamp,
                new MoneyRequest(amount, currency),
                new MerchantInfoRequest(
                        "merchant-e2e-" + TOPIC_SUFFIX,
                        "High Risk Travel Outlet",
                        "4722",
                        "Travel",
                        "GB",
                        "ECOMMERCE",
                        false,
                        Map.of("merchantRiskTier", "high")
                ),
                new DeviceInfoRequest(
                        deviceId,
                        "fp-e2e-" + TOPIC_SUFFIX,
                        "198.51.100.77",
                        "Mozilla/5.0 (X11; Linux x86_64)",
                        "WEB",
                        "CHROME",
                        false,
                        true,
                        true,
                        Map.of("emulatorDetected", false)
                ),
                new LocationInfoRequest(
                        "GB",
                        "England",
                        "London",
                        "SW1A1AA",
                        51.5074,
                        -0.1278,
                        "Europe/London",
                        false
                ),
                new CustomerContextRequest(
                        customerId,
                        accountId,
                        "PREMIUM",
                        "example.com",
                        620,
                        true,
                        true,
                        "US",
                        "USD",
                        List.of("known-device-1", "known-device-2"),
                        Map.of("kycLevel", "FULL")
                ),
                "PURCHASE",
                "3DS",
                "PAYMENT_GATEWAY",
                "trace-e2e-" + TOPIC_SUFFIX,
                Map.of("channel", "web")
        );
    }

    private ResponseEntity<IngestTransactionResponse> submitTransaction(
            IngestTransactionRequest request,
            String correlationId
    ) {
        return restTemplate.exchange(
                RequestEntity.post(URI.create(ingestUrl("/api/v1/transactions")))
                        .header("X-Correlation-Id", correlationId)
                        .body(request),
                IngestTransactionResponse.class
        );
    }

    private Optional<AlertSummaryResponse> findAlertSummary(String transactionId) {
        ResponseEntity<PagedResponse<AlertSummaryResponse>> response = restTemplate.exchange(
                alertUrl("/api/v1/alerts?page=0&size=100"),
                HttpMethod.GET,
                analystApiRequest(),
                new ParameterizedTypeReference<>() {
                }
        );

        PagedResponse<AlertSummaryResponse> body = response.getBody();
        if (body == null || body.content() == null) {
            return Optional.empty();
        }

        return body.content().stream()
                .filter(alert -> transactionId.equals(alert.transactionId()))
                .findFirst();
    }

    private <T> ConsumerRecord<String, T> awaitKafkaRecord(String topic, Class<T> valueType, String transactionId) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "e2e-" + topic + "-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = topicPartitions(topic);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + E2E_WAIT_TIMEOUT.toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (transactionId.equals(record.key())) {
                        return new ConsumerRecord<>(
                                record.topic(),
                                record.partition(),
                                record.offset(),
                                record.key(),
                                deserializeKafkaValue(record.value(), valueType)
                        );
                    }
                }
            }
        }

        throw new AssertionError("No Kafka record for transaction " + transactionId + " was published to topic " + topic + ".");
    }

    private List<TopicPartition> topicPartitions(String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (int partition = 0; partition < PLATFORM_TOPIC_PARTITIONS; partition++) {
            partitions.add(new TopicPartition(topic, partition));
        }
        return partitions;
    }

    private <T> T deserializeKafkaValue(String payload, Class<T> valueType) {
        try {
            return new JacksonKafkaDeserializer<>(valueType).deserialize(
                    "e2e-test",
                    payload.getBytes(StandardCharsets.UTF_8)
            );
        } catch (RuntimeException exception) {
            throw new AssertionError("Kafka record payload could not be deserialized as " + valueType.getSimpleName() + ".", exception);
        }
    }

    private <T> T awaitCondition(Supplier<Optional<T>> supplier) {
        long deadline = System.nanoTime() + E2E_WAIT_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            Optional<T> value = supplier.get();
            if (value.isPresent()) {
                return value.get();
            }

            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for E2E assertion.", exception);
            }
        }

        throw new AssertionError("Condition was not met within the timeout.");
    }

    private HttpEntity<Void> analystApiRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(DemoAuthHeaders.USER_ID, E2E_ANALYST_USER);
        headers.add(DemoAuthHeaders.ROLES, E2E_ANALYST_ROLE);
        return new HttpEntity<>(headers);
    }
}
