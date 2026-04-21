package com.frauddetection.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "app.replay.jsonl")
public record JsonlReplayProperties(
        @NotBlank String file
) {
}
