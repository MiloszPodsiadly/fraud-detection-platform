package com.frauddetection.simulator.controller;

import com.frauddetection.simulator.api.ReplayStartRequest;
import com.frauddetection.simulator.api.ReplayStatusResponse;
import com.frauddetection.simulator.api.ReplayStopResponse;
import com.frauddetection.simulator.service.TransactionReplayUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/replay")
public class TransactionReplayController {

    private final TransactionReplayUseCase transactionReplayUseCase;

    public TransactionReplayController(TransactionReplayUseCase transactionReplayUseCase) {
        this.transactionReplayUseCase = transactionReplayUseCase;
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayStatusResponse startReplay(@Valid @RequestBody ReplayStartRequest request) {
        return transactionReplayUseCase.startReplay(request);
    }

    @PostMapping("/stop")
    public ReplayStopResponse stopReplay() {
        return transactionReplayUseCase.stopReplay();
    }

    @GetMapping("/status")
    public ReplayStatusResponse getReplayStatus() {
        return transactionReplayUseCase.getReplayStatus();
    }
}
