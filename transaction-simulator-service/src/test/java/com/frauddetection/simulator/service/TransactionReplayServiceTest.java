package com.frauddetection.simulator.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.simulator.api.ReplaySourceType;
import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.api.ReplayStatusResponse;
import com.frauddetection.simulator.api.ReplayStopResponse;
import com.frauddetection.simulator.config.ReplayProperties;
import com.frauddetection.simulator.messaging.TransactionRawEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionReplayServiceTest {

    @Test
    void shouldStartReplayAndCompleteSynchronously() {
        RecordingPublisher publisher = new RecordingPublisher();
        TransactionReplayService service = new TransactionReplayService(
                List.of(new StubReplayDataSource(ReplaySourceType.JSONL, 3)),
                publisher,
                new SyncTaskExecutor(),
                new ReplayProperties(0L, "jsonl")
        );

        ReplayStatusResponse response = service.startReplay(new ReplayStartRequest(ReplaySourceType.JSONL, 3, 0L));

        assertThat(response.state()).isEqualTo("RUNNING");
        ReplayStatusResponse finalStatus = service.getReplayStatus();
        assertThat(finalStatus.state()).isEqualTo("COMPLETED");
        assertThat(finalStatus.processedEvents()).isEqualTo(3);
        assertThat(finalStatus.publishedEvents()).isEqualTo(3);
        assertThat(publisher.publishedEvents).hasSize(3);
    }

    @Test
    void shouldFailWhenReplaySourceIsMissing() {
        TransactionReplayService service = new TransactionReplayService(
                List.of(),
                event -> { },
                new SyncTaskExecutor(),
                new ReplayProperties(0L, "jsonl")
        );

        ReplayStatusResponse response = service.startReplay(new ReplayStartRequest(ReplaySourceType.JSONL, 2, 0L));

        assertThat(response.state()).isEqualTo("RUNNING");
        ReplayStatusResponse finalStatus = service.getReplayStatus();
        assertThat(finalStatus.state()).isEqualTo("FAILED");
        assertThat(finalStatus.message()).isEqualTo("Replay source is not configured.");
    }

    @Test
    void shouldReturnNoActiveReplayOnStopWhenIdle() {
        TransactionReplayService service = new TransactionReplayService(
                List.of(new StubReplayDataSource(ReplaySourceType.JSONL, 1)),
                event -> { },
                new SyncTaskExecutor(),
                new ReplayProperties(0L, "jsonl")
        );

        ReplayStopResponse response = service.stopReplay();

        assertThat(response.state()).isEqualTo("IDLE");
    }

    @Test
    void shouldRejectSecondReplayWhenAlreadyRunning() {
        TransactionReplayService service = new TransactionReplayService(
                List.of(new BlockingReplayDataSource()),
                event -> { },
                runnable -> new Thread(runnable).start(),
                new ReplayProperties(0L, "jsonl")
        );

        service.startReplay(new ReplayStartRequest(ReplaySourceType.JSONL, 1, 0L));

        assertThatThrownBy(() -> service.startReplay(new ReplayStartRequest(ReplaySourceType.JSONL, 1, 0L)))
                .hasMessage("Replay is already running.");
    }

    private record StubReplayDataSource(ReplaySourceType sourceType, int eventCount) implements ReplayDataSource {
        @Override
        public Stream<TransactionRawEvent> stream(int maxEvents) {
            return Stream.generate(() -> new TransactionRawEvent(
                    "event",
                    "txn",
                    "corr",
                    "cust",
                    "acct",
                    "card",
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    "PURCHASE",
                    "3DS",
                    "SIMULATOR",
                    "trace",
                    Map.of()
            )).limit(Math.min(maxEvents, eventCount));
        }
    }

    private static final class RecordingPublisher implements TransactionRawEventPublisher {
        private final java.util.List<TransactionRawEvent> publishedEvents = new java.util.ArrayList<>();

        @Override
        public void publish(TransactionRawEvent event) {
            publishedEvents.add(event);
        }
    }

    private static final class BlockingReplayDataSource implements ReplayDataSource {
        @Override
        public ReplaySourceType sourceType() {
            return ReplaySourceType.JSONL;
        }

        @Override
        public Stream<TransactionRawEvent> stream(int maxEvents) {
            return Stream.generate(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new TransactionRawEvent(
                        "event",
                        "txn",
                        "corr",
                        "cust",
                        "acct",
                        "card",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "PURCHASE",
                        "3DS",
                        "SIMULATOR",
                        "trace",
                        Map.of()
                );
            }).limit(maxEvents);
        }
    }
}
