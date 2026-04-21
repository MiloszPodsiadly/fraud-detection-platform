package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class HttpMlModelScoringClient implements MlModelScoringClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMlModelScoringClient.class);

    private final RestClient mlModelRestClient;

    public HttpMlModelScoringClient(RestClient mlModelRestClient) {
        this.mlModelRestClient = mlModelRestClient;
    }

    @Override
    public MlModelOutput score(MlModelInput input) {
        try {
            MlModelOutput output = mlModelRestClient
                    .post()
                    .uri("/v1/fraud/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(input)
                    .retrieve()
                    .body(MlModelOutput.class);

            if (output == null) {
                return unavailable("ML inference service returned an empty response.");
            }
            return output;
        } catch (RestClientException exception) {
            log.atWarn()
                    .addKeyValue("transactionId", input.transactionId())
                    .addKeyValue("correlationId", input.correlationId())
                    .setCause(exception)
                    .log("ML inference request failed; rule-based fallback remains available.");
            return unavailable("ML inference service request failed.");
        }
    }

    private MlModelOutput unavailable(String fallbackReason) {
        return new MlModelOutput(
                false,
                0.0d,
                RiskLevel.LOW,
                "python-fraud-model",
                "unavailable",
                Instant.now(),
                List.of("ML_MODEL_UNAVAILABLE"),
                Map.of(
                        "modelAvailable", false,
                        "fallbackReason", fallbackReason
                ),
                Map.of(
                        "engineType", "ML_HTTP_CLIENT",
                        "explanationType", "NO_MODEL_INFERENCE",
                        "modelAvailable", false,
                        "fallbackReason", fallbackReason
                ),
                fallbackReason
        );
    }
}
