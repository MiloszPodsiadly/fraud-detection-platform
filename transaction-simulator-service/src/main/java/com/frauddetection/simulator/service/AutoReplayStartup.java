package com.frauddetection.simulator.service;

import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.config.AutoReplayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AutoReplayStartup {

    private static final Logger log = LoggerFactory.getLogger(AutoReplayStartup.class);

    private final AutoReplayProperties autoReplayProperties;
    private final TransactionReplayUseCase transactionReplayUseCase;
    private final TaskExecutor replayTaskExecutor;

    public AutoReplayStartup(
            AutoReplayProperties autoReplayProperties,
            TransactionReplayUseCase transactionReplayUseCase,
            TaskExecutor replayTaskExecutor
    ) {
        this.autoReplayProperties = autoReplayProperties;
        this.transactionReplayUseCase = transactionReplayUseCase;
        this.replayTaskExecutor = replayTaskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startReplayWhenReady() {
        if (!autoReplayProperties.enabled()) {
            log.info("Automatic replay bootstrap is disabled.");
            return;
        }

        replayTaskExecutor.execute(() -> {
            delayBeforeStart();
            ReplayStartRequest request = new ReplayStartRequest(
                    autoReplayProperties.sourceType(),
                    autoReplayProperties.maxEvents(),
                    autoReplayProperties.throttleMillis()
            );
            log.atInfo()
                    .addKeyValue("sourceType", request.sourceType())
                    .addKeyValue("maxEvents", request.maxEvents())
                    .addKeyValue("throttleMillis", request.throttleMillis())
                    .log("Starting automatic replay bootstrap.");
            transactionReplayUseCase.startReplay(request);
        });
    }

    private void delayBeforeStart() {
        Long delayMillis = autoReplayProperties.startDelayMillis();
        if (delayMillis == null || delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
