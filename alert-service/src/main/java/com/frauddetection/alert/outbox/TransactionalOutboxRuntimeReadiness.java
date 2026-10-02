package com.frauddetection.alert.outbox;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class TransactionalOutboxRuntimeReadiness implements ApplicationListener<ApplicationReadyEvent> {

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    public void markPreflightPassed() {
        state.compareAndSet(State.PENDING, State.PREFLIGHT_PASSED);
    }

    public void markFailed() {
        state.set(State.FAILED);
    }

    public boolean isReady() {
        return state.get() == State.READY;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        state.compareAndSet(State.PREFLIGHT_PASSED, State.READY);
    }

    public void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("Transactional outbox runtime startup readiness is not complete.");
        }
    }

    private enum State {
        PENDING,
        PREFLIGHT_PASSED,
        READY,
        FAILED
    }
}
