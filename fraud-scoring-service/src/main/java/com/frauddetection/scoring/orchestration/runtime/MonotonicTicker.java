package com.frauddetection.scoring.orchestration.runtime;

@FunctionalInterface
public interface MonotonicTicker {
    long readNanos();

    static MonotonicTicker system() {
        return System::nanoTime;
    }
}
