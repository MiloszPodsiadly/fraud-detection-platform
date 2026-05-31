package com.frauddetection.scoring.orchestration.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedFraudEngineExecutorSafetyTest {

    @Test
    void executorRejectsInvalidPoolSize() {
        assertThatThrownBy(() -> new BoundedFraudEngineExecutor(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxPoolSize must be positive");
    }

    @Test
    void executorRejectsInvalidQueueSize() {
        assertThatThrownBy(() -> new BoundedFraudEngineExecutor(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queueCapacity must be positive");
    }

    @Test
    void executorCanBeShutdown() {
        BoundedFraudEngineExecutor executor = new BoundedFraudEngineExecutor(1, 1);

        executor.shutdown();

        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void executorRestoresInterruptFlagWhenInterrupted() {
        StubFuture<String> future = StubFuture.interrupted();
        BoundedFraudEngineExecutor executor = new BoundedFraudEngineExecutor(new StubExecutorService(future));

        try {
            assertThat(executor.execute(() -> "unused", Duration.ofMillis(1)).status())
                    .isEqualTo(BoundedFraudEngineExecutor.ExecutionStatus.FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(future.cancelled).isTrue();
        } finally {
            Thread.interrupted();
            executor.close();
        }
    }

    @Test
    void timeoutAttemptsCancellation() {
        StubFuture<String> future = StubFuture.timedOut();
        try (BoundedFraudEngineExecutor executor =
                     new BoundedFraudEngineExecutor(new StubExecutorService(future))) {
            assertThat(executor.execute(() -> "unused", Duration.ofMillis(1)).status())
                    .isEqualTo(BoundedFraudEngineExecutor.ExecutionStatus.TIMED_OUT);
            assertThat(future.cancelled).isTrue();
        }
    }

    @Test
    void cancellationFailureStillReturnsBoundedTimeout() {
        StubFuture<String> future = StubFuture.cancellationFails();
        try (BoundedFraudEngineExecutor executor =
                     new BoundedFraudEngineExecutor(new StubExecutorService(future))) {
            assertThat(executor.execute(() -> "unused", Duration.ofMillis(1)).status())
                    .isEqualTo(BoundedFraudEngineExecutor.ExecutionStatus.TIMED_OUT);
        }
    }

    @Test
    void boundedExecutorDoesNotUseForbiddenConcurrencyConstructs() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/frauddetection/scoring/orchestration/runtime/BoundedFraudEngineExecutor.java"
        ));

        assertThat(source)
                .contains("ArrayBlockingQueue")
                .contains("shutdownNow")
                .doesNotContain(
                        "new Thread(",
                        "newCachedThreadPool",
                        "ForkJoinPool.commonPool",
                        "parallelStream",
                        "LinkedBlockingQueue",
                        "CompletableFuture.supplyAsync"
                );
    }

    private static final class StubExecutorService extends AbstractExecutorService {
        private final Future<?> future;
        private boolean shutdown;

        private StubExecutorService(Future<?> future) {
            this.future = future;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Future<T> submit(Callable<T> task) {
            return (Future<T>) future;
        }
    }

    private static final class StubFuture<T> implements Future<T> {
        private final boolean timeout;
        private final boolean interrupted;
        private final boolean cancellationFails;
        private boolean cancelled;

        private StubFuture(boolean timeout, boolean interrupted, boolean cancellationFails) {
            this.timeout = timeout;
            this.interrupted = interrupted;
            this.cancellationFails = cancellationFails;
        }

        static <T> StubFuture<T> timedOut() {
            return new StubFuture<>(true, false, false);
        }

        static <T> StubFuture<T> interrupted() {
            return new StubFuture<>(false, true, false);
        }

        static <T> StubFuture<T> cancellationFails() {
            return new StubFuture<>(true, false, true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancellationFails) {
                throw new IllegalStateException("raw cancellation detail");
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public T get() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public T get(long timeoutValue, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            if (interrupted) {
                throw new InterruptedException("bounded");
            }
            if (timeout) {
                throw new TimeoutException("bounded");
            }
            throw new UnsupportedOperationException("not used");
        }
    }
}
