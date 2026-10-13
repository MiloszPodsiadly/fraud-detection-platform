package com.frauddetection.alert.config;

import com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
@EnableConfigurationProperties({
        KafkaTopicProperties.class,
        KafkaConsumerProperties.class,
        TransactionScoredRecoveryProperties.class,
        MlPredictionEvidenceRecoveryProperties.class,
        EngineIntelligenceRecoveryProperties.class,
        EngineIntelligencePendingProjectionProperties.class,
        AssistantProperties.class
})
public class KafkaTopicProvisioningConfiguration {

    private static final int PLATFORM_TOPIC_PARTITIONS = 3;
    private static final short PLATFORM_TOPIC_REPLICAS = 1;

    @Bean
    public KafkaAdmin.NewTopics alertTopics(
            KafkaTopicProperties topics,
            MlPredictionEvidenceRecoveryProperties mlRecovery,
            EngineIntelligenceRecoveryProperties engineRecovery,
            TransactionScoredRecoveryProperties scoredRecovery
    ) {
        return new KafkaAdmin.NewTopics(
                topic(topics.transactionScored()),
                topic(topics.fraudAlerts()),
                topic(topics.fraudDecisions()),
                topic(topics.transactionsDeadLetter()),
                topic(scoredRecovery.redriveTopic()),
                topic(scoredRecovery.quarantineTopic()),
                topic(mlRecovery.deadLetterTopic()),
                topic(mlRecovery.redriveTopic()),
                topic(mlRecovery.quarantineTopic()),
                topic(engineRecovery.deadLetterTopic()),
                topic(engineRecovery.redriveTopic()),
                topic(engineRecovery.quarantineTopic())
        );
    }

    private NewTopic topic(String name) {
        return new NewTopic(name, PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS);
    }
}
