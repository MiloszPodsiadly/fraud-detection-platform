package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionScoredEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionScoredEventListener.class);

    private final AlertManagementUseCase alertManagementUseCase;
    private final TransactionMonitoringUseCase transactionMonitoringUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public TransactionScoredEventListener(
            AlertManagementUseCase alertManagementUseCase,
            TransactionMonitoringUseCase transactionMonitoringUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.alertManagementUseCase = alertManagementUseCase;
        this.transactionMonitoringUseCase = transactionMonitoringUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-scored}",
            containerFactory = "transactionScoredKafkaListenerContainerFactory"
    )
    public void onMessage(TransactionScoredEvent event) {
        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("topic", kafkaTopicProperties.transactionScored())
                .log("Received scored transaction event for alert handling.");
        transactionMonitoringUseCase.recordScoredTransaction(event);
        alertManagementUseCase.handleScoredTransaction(event);
    }
}
