package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult;
import com.frauddetection.alert.service.AlertManagementUseCase;
import com.frauddetection.alert.service.TransactionMonitoringUseCase;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.observability.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(transactionManager = "mongoTransactionManager")
    public void onMessage(
            TransactionScoredEvent event,
            @Header(name = TraceContext.KAFKA_TRACE_ID_HEADER, required = false) String traceId
    ) {
        try (TraceContext.Scope ignored = TraceContext.open(event.correlationId(), traceId, event.transactionId(), null)) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", kafkaTopicProperties.transactionScored())
                    .log("Received scored transaction event for alert handling.");
            ScoringOccurrenceAdmissionResult admission = transactionMonitoringUseCase.recordScoredTransaction(event);
            switch (admission.outcome()) {
                case APPLIED_NEW, APPLIED_NEWER, IDEMPOTENT_REPLAY ->
                        alertManagementUseCase.handleScoredTransaction(event);
                case STALE_REJECTED -> log.atInfo()
                        .addKeyValue("reason", admission.reasonCode())
                        .log("Skipped stale scored transaction occurrence.");
                case CONFLICT_REJECTED -> throw new ScoringOccurrenceConflictException(admission.reasonCode());
            }
        }
    }
}
