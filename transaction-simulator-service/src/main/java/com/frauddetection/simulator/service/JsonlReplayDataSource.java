package com.frauddetection.simulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.simulator.api.ReplaySourceType;
import com.frauddetection.simulator.config.JsonlReplayProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Component
public class JsonlReplayDataSource implements ReplayDataSource {

    private final ObjectMapper objectMapper;
    private final JsonlReplayProperties jsonlReplayProperties;

    public JsonlReplayDataSource(ObjectMapper objectMapper, JsonlReplayProperties jsonlReplayProperties) {
        this.objectMapper = objectMapper;
        this.jsonlReplayProperties = jsonlReplayProperties;
    }

    @Override
    public ReplaySourceType sourceType() {
        return ReplaySourceType.JSONL;
    }

    @Override
    public Stream<TransactionRawEvent> stream(int maxEvents) {
        try {
            return Files.lines(Path.of(jsonlReplayProperties.file()))
                    .filter(Predicate.not(String::isBlank))
                    .limit(maxEvents)
                    .map(this::readEvent);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load JSONL replay file.", exception);
        }
    }

    private TransactionRawEvent readEvent(String line) {
        try {
            return objectMapper.readValue(line, TransactionRawEvent.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse JSONL replay event.", exception);
        }
    }
}
