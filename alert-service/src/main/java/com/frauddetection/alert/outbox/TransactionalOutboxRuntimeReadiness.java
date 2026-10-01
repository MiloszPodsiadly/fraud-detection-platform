package com.frauddetection.alert.outbox;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class TransactionalOutboxRuntimeReadiness {

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public void markReady() {
        state.compareAndSet(State.PENDING, State.READY);
    }

    public void markFailed() {
        state.set(State.FAILED);
    }

    public boolean isReady() {
        return state.get() == State.READY;
    }

    public void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("Transactional outbox persisted-contract preflight is not complete.");
        }
    }

    private enum State {
        PENDING,
        READY,
        FAILED
    }
}
