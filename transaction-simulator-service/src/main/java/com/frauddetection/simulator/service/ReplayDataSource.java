package com.frauddetection.simulator.service;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.simulator.api.ReplaySourceType;

import java.util.stream.Stream;

public interface ReplayDataSource {

    ReplaySourceType sourceType();

    Stream<TransactionRawEvent> stream(int maxEvents);
}
