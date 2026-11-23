package com.frauddetection.common.events.source;

import com.mongodb.TransactionOptions;
import com.mongodb.client.MongoCollection;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** One monotonic deadline shared by a bounded owner read and its Mongo operations. */
public final class MongoOperationBudget {
    private final long startedNanos;
    private final long budgetNanos;

    private MongoOperationBudget(Duration budget) {
        Objects.requireNonNull(budget, "MONGO_OPERATION_BUDGET_REQUIRED");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("MONGO_OPERATION_BUDGET_INVALID");
        }
        long nanos;
        try { nanos = budget.toNanos(); }
        catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
        this.startedNanos = System.nanoTime();
        this.budgetNanos = nanos;
    }

    public static MongoOperationBudget starting(Duration budget) {
        return new MongoOperationBudget(budget);
    }

    public long remainingNanos() {
        long remaining = budgetNanos - (System.nanoTime() - startedNanos);
        if (remaining <= 0) { throw new IllegalStateException("MONGO_OPERATION_DEADLINE_EXCEEDED"); }
        return remaining;
    }

    public Duration remaining() { return Duration.ofNanos(remainingNanos()); }

    public boolean expired() {
        return budgetNanos - (System.nanoTime() - startedNanos) <= 0;
    }

    public void check() { remainingNanos(); }

    public <T> MongoCollection<T> withTimeout(MongoCollection<T> collection) {
        return Objects.requireNonNull(collection, "MONGO_COLLECTION_REQUIRED")
                .withTimeout(remainingNanos(), TimeUnit.NANOSECONDS);
    }

    public TransactionOptions transactionOptions(TransactionOptions base) {
        Objects.requireNonNull(base, "MONGO_TRANSACTION_OPTIONS_REQUIRED");
        TransactionOptions.Builder builder = TransactionOptions.builder()
                .readConcern(base.getReadConcern())
                .writeConcern(base.getWriteConcern())
                .readPreference(base.getReadPreference())
                .timeout(remainingNanos(), TimeUnit.NANOSECONDS);
        Long maxCommitNanos = base.getMaxCommitTime(TimeUnit.NANOSECONDS);
        if (maxCommitNanos != null) { builder.maxCommitTime(maxCommitNanos, TimeUnit.NANOSECONDS); }
        return builder.build();
    }

    @Override public String toString() { return "MongoOperationBudget[remaining=redacted]"; }
}
