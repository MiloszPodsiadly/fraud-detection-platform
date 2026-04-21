package com.frauddetection.alert.integration;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class AlertServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, TransactionScoredEvent> transactionScoredEventKafkaTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.data.mongodb.uri", () -> FraudPlatformContainers.mongodb().getReplicaSetUrl("alert_service_test"));
        registry.add("app.kafka.topics.transaction-scored", () -> "transactions.scored.alert.test");
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.test");
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.test");
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.alert.test");
    }

    @Test
    void shouldStartContextWithMongoAndKafka() {
        transactionScoredEventKafkaTemplate.send("transactions.scored.alert.test", "txn-1", TransactionFixtures.scoredTransaction().build());
        transactionScoredEventKafkaTemplate.flush();
        assertThat(new RestTemplate()).isNotNull();
    }
}
