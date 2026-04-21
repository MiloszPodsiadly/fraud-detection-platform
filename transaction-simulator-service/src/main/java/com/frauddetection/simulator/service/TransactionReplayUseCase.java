package com.frauddetection.simulator.service;

import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.api.ReplayStatusResponse;
import com.frauddetection.simulator.api.ReplayStopResponse;

public interface TransactionReplayUseCase {

    ReplayStatusResponse startReplay(ReplayStartRequest request);

    ReplayStopResponse stopReplay();

    ReplayStatusResponse getReplayStatus();
}
