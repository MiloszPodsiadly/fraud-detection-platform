package com.frauddetection.simulator.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.simulator.api.ReplaySourceType;
import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.api.ReplayStatusResponse;
import com.frauddetection.simulator.api.ReplayStopResponse;
import com.frauddetection.simulator.config.ReplayProperties;
import com.frauddetection.simulator.domain.ReplayRun;
import com.frauddetection.simulator.domain.ReplayState;
import com.frauddetection.simulator.exception.ReplayAlreadyRunningException;
import com.frauddetection.simulator.messaging.TransactionRawEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TransactionReplayService implements TransactionReplayUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionReplayService.class);

    private final Map<ReplaySourceType, ReplayDataSource> replayDataSources;
    private final TransactionRawEventPublisher transactionRawEventPublisher;
    private final TaskExecutor replayTaskExecutor;
    private final ReplayProperties replayProperties;

    private final AtomicReference<ReplayRun> replayRun = new AtomicReference<>(ReplayRun.idle());
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public TransactionReplayService(
            List<ReplayDataSource> replayDataSources,
            TransactionRawEventPublisher transactionRawEventPublisher,
            TaskExecutor replayTaskExecutor,
            ReplayProperties replayProperties
    ) {
        this.replayDataSources = toMap(replayDataSources);
        this.transactionRawEventPublisher = transactionRawEventPublisher;
        this.replayTaskExecutor = replayTaskExecutor;
        this.replayProperties = replayProperties;
    }

    @Override
    public ReplayStatusResponse startReplay(ReplayStartRequest request) {
        ReplayRun current = replayRun.get();
        if (current.state() == ReplayState.RUNNING || current.state() == ReplayState.STOPPING) {
            throw new ReplayAlreadyRunningException("Replay is already running.");
        }

        stopRequested.set(false);
        ReplayRun startedRun = new ReplayRun(
                ReplayState.RUNNING,
                request.sourceType(),
                request.maxEvents(),
                0,
                0,
                0,
                request.throttleMillis(),
                Instant.now(),
                null,
                "Replay started."
        );
        replayRun.set(startedRun);

        replayTaskExecutor.execute(() -> executeReplay(request));

        return toResponse(startedRun);
    }

    @Override
    public ReplayStopResponse stopReplay() {
        ReplayRun current = replayRun.get();
        if (current.state() != ReplayState.RUNNING) {
            return new ReplayStopResponse(current.state().name(), "No active replay is running.");
        }

        stopRequested.set(true);
        replayRun.updateAndGet(run -> new ReplayRun(
                ReplayState.STOPPING,
                run.sourceType(),
                run.requestedEvents(),
                run.processedEvents(),
                run.publishedEvents(),
                run.failedEvents(),
                run.throttleMillis(),
                run.startedAt(),
                run.finishedAt(),
                "Replay stop requested."
        ));

        return new ReplayStopResponse(ReplayState.STOPPING.name(), "Replay stop requested.");
    }

    @Override
    public ReplayStatusResponse getReplayStatus() {
        return toResponse(replayRun.get());
    }

    private void executeReplay(ReplayStartRequest request) {
        ReplayDataSource replayDataSource = replayDataSources.get(request.sourceType());
        if (replayDataSource == null) {
            replayRun.set(new ReplayRun(
                    ReplayState.FAILED,
                    request.sourceType(),
                    request.maxEvents(),
                    0,
                    0,
                    0,
                    request.throttleMillis(),
                    Instant.now(),
                    Instant.now(),
                    "Replay source is not configured."
            ));
            return;
        }

        long processed = 0;
        long published = 0;
        long failed = 0;

        try (var stream = replayDataSource.stream(request.maxEvents())) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (stopRequested.get()) {
                    replayRun.set(new ReplayRun(
                            ReplayState.COMPLETED,
                            request.sourceType(),
                            request.maxEvents(),
                            processed,
                            published,
                            failed,
                            request.throttleMillis(),
                            replayRun.get().startedAt(),
                            Instant.now(),
                            "Replay stopped by request."
                    ));
                    return;
                }

                TransactionRawEvent event = iterator.next();
                processed++;
                try {
                    transactionRawEventPublisher.publish(event);
                    published++;
                } catch (Exception exception) {
                    failed++;
                    log.atWarn()
                            .addKeyValue("transactionId", event.transactionId())
                            .addKeyValue("sourceType", request.sourceType())
                            .setCause(exception)
                            .log("Failed to publish replay event.");
                }

                updateProgress(request, processed, published, failed, "Replay in progress.");
                sleep(request.throttleMillis() != null ? request.throttleMillis() : replayProperties.throttleMillis());
            }

            replayRun.set(new ReplayRun(
                    ReplayState.COMPLETED,
                    request.sourceType(),
                    request.maxEvents(),
                    processed,
                    published,
                    failed,
                    request.throttleMillis(),
                    replayRun.get().startedAt(),
                    Instant.now(),
                    "Replay completed."
            ));
        } catch (Exception exception) {
            replayRun.set(new ReplayRun(
                    ReplayState.FAILED,
                    request.sourceType(),
                    request.maxEvents(),
                    processed,
                    published,
                    failed,
                    request.throttleMillis(),
                    replayRun.get().startedAt(),
                    Instant.now(),
                    "Replay failed."
            ));
            log.atError()
                    .addKeyValue("sourceType", request.sourceType())
                    .setCause(exception)
                    .log("Replay execution failed.");
        }
    }

    private void updateProgress(ReplayStartRequest request, long processed, long published, long failed, String message) {
        replayRun.set(new ReplayRun(
                ReplayState.RUNNING,
                request.sourceType(),
                request.maxEvents(),
                processed,
                published,
                failed,
                request.throttleMillis(),
                replayRun.get().startedAt(),
                null,
                message
        ));
    }

    private void sleep(Long throttleMillis) {
        if (throttleMillis == null || throttleMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(throttleMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ReplayStatusResponse toResponse(ReplayRun run) {
        return new ReplayStatusResponse(
                run.state().name(),
                run.sourceType(),
                run.requestedEvents(),
                run.processedEvents(),
                run.publishedEvents(),
                run.failedEvents(),
                run.throttleMillis(),
                run.startedAt(),
                run.finishedAt(),
                run.message()
        );
    }

    private Map<ReplaySourceType, ReplayDataSource> toMap(List<ReplayDataSource> dataSources) {
        Map<ReplaySourceType, ReplayDataSource> result = new EnumMap<>(ReplaySourceType.class);
        dataSources.forEach(dataSource -> result.put(dataSource.sourceType(), dataSource));
        return result;
    }
}
