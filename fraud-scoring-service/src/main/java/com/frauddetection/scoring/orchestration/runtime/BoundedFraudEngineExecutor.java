package com.frauddetection.scoring.orchestration.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedFraudEngineExecutor implements AutoCloseable {
    private static final int DEFAULT_MAX_POOL_SIZE = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 2;
    private static final long IDLE_THREAD_TIMEOUT_SECONDS = 1L;

    private final ExecutorService executorService;

    public BoundedFraudEngineExecutor(int maxPoolSize, int queueCapacity) {
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        ThreadPoolExecutor ownedExecutor = new ThreadPoolExecutor(
                maxPoolSize,
                maxPoolSize,
                IDLE_THREAD_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedDaemonThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        ownedExecutor.allowCoreThreadTimeOut(true);
        this.executorService = ownedExecutor;
    }

    BoundedFraudEngineExecutor(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "executorService is required");
    }

    public static BoundedFraudEngineExecutor defaultInternalExecutor() {
        return new BoundedFraudEngineExecutor(DEFAULT_MAX_POOL_SIZE, DEFAULT_QUEUE_CAPACITY);
    }

    public <T> ExecutionResult<T> execute(Callable<T> task, Duration deadline) {
        Objects.requireNonNull(task, "task is required");
        FraudEngineExecutionPolicy.validateDeadline(deadline);
        Future<T> future;
        try {
            future = executorService.submit(task);
        } catch (RejectedExecutionException exception) {
            return ExecutionResult.failed();
        }
        try {
            return ExecutionResult.completed(future.get(deadline.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException exception) {
            cancelIfPossible(future);
            return ExecutionResult.timedOut();
        } catch (InterruptedException exception) {
            cancelIfPossible(future);
            Thread.currentThread().interrupt();
            return ExecutionResult.failed();
        } catch (ExecutionException | RuntimeException exception) {
            return ExecutionResult.failed();
        }
    }

    private void cancelIfPossible(Future<?> future) {
        try {
            future.cancel(true);
        } catch (RuntimeException exception) {
            // Cancellation is cooperative and best-effort.
        }
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    public boolean isShutdown() {
        return executorService.isShutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    private static ThreadFactory namedDaemonThreadFactory() {
        ThreadFactory delegate = Executors.defaultThreadFactory();
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName("fraud-orchestrator-engine-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public enum ExecutionStatus {
        COMPLETED,
        FAILED,
        TIMED_OUT
    }

    public record ExecutionResult<T>(ExecutionStatus status, T value) {
        public ExecutionResult {
            Objects.requireNonNull(status, "status is required");
            if (status != ExecutionStatus.COMPLETED && value != null) {
                throw new IllegalArgumentException("non-completed execution must not expose a value");
            }
        }

        static <T> ExecutionResult<T> completed(T value) {
            return new ExecutionResult<>(ExecutionStatus.COMPLETED, value);
        }

        static <T> ExecutionResult<T> failed() {
            return new ExecutionResult<>(ExecutionStatus.FAILED, null);
        }

        static <T> ExecutionResult<T> timedOut() {
            return new ExecutionResult<>(ExecutionStatus.TIMED_OUT, null);
        }
    }
}
