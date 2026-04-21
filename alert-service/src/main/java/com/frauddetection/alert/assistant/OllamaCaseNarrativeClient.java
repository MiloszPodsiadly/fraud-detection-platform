package com.frauddetection.alert.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.config.AssistantProperties;
import com.frauddetection.alert.domain.AlertCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OllamaCaseNarrativeClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaCaseNarrativeClient.class);

    private final RestClient ollamaRestClient;
    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;

    public OllamaCaseNarrativeClient(
            RestClient ollamaRestClient,
            AssistantProperties assistantProperties,
            ObjectMapper objectMapper
    ) {
        this.ollamaRestClient = ollamaRestClient;
        this.assistantProperties = assistantProperties;
        this.objectMapper = objectMapper;
    }

    public Optional<OllamaCaseNarrative> generate(AlertCase alert, AnalystCaseSummaryResponse deterministicSummary) {
        try {
            byte[] responseBody = ollamaRestClient
                    .post()
                    .uri("/api/generate")
                    .body(new OllamaGenerateRequest(
                            assistantProperties.ollamaModel(),
                            prompt(alert, deterministicSummary),
                            false,
                            "json",
                            Map.of(
                                    "temperature", 0.1d,
                                    "num_predict", 220
                            )
                    ))
                    .retrieve()
                    .body(byte[].class);

            if (responseBody == null || responseBody.length == 0) {
                return Optional.empty();
            }

            OllamaGenerateResponse response = objectMapper.readValue(new String(responseBody, StandardCharsets.UTF_8), OllamaGenerateResponse.class);
            if (response.response() == null || response.response().isBlank()) {
                return Optional.empty();
            }

            return Optional.of(parseNarrative(response.response()));
        } catch (RestClientException | JsonProcessingException exception) {
            log.atWarn()
                    .addKeyValue("alertId", alert.alertId())
                    .addKeyValue("transactionId", alert.transactionId())
                    .addKeyValue("ollamaModel", assistantProperties.ollamaModel())
                    .setCause(exception)
                    .log("Ollama assistant narrative failed; deterministic assistant summary will be used.");
            return Optional.empty();
        }
    }

    private OllamaCaseNarrative parseNarrative(String response) throws JsonProcessingException {
        String json = extractJson(response);
        OllamaNarrativePayload payload = objectMapper.readValue(json, OllamaNarrativePayload.class);
        return new OllamaCaseNarrative(
                payload.overview(),
                payload.keyObservations() == null ? List.of() : payload.keyObservations(),
                payload.recommendedActionRationale(),
                payload.uncertainty(),
                assistantProperties.ollamaModel(),
                "local"
        );
    }

    private String extractJson(String response) {
        int first = response.indexOf('{');
        int last = response.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return response.substring(first, last + 1);
        }
        return response;
    }

    private String prompt(AlertCase alert, AnalystCaseSummaryResponse summary) throws JsonProcessingException {
        Map<String, Object> caseData = Map.of(
                "alertId", alert.alertId(),
                "transactionId", alert.transactionId(),
                "customerId", alert.customerId(),
                "riskLevel", alert.riskLevel() == null ? "UNKNOWN" : alert.riskLevel().name(),
                "fraudScore", alert.fraudScore(),
                "reasonCodes", alert.reasonCodes() == null ? List.of() : alert.reasonCodes(),
                "transaction", summary.transactionSummary(),
                "behavior", summary.customerRecentBehaviorSummary(),
                "recommendedAction", summary.recommendedNextAction()
        );

        return """
                You are an internal fraud analyst assistant. Generate a concise analyst-facing case narrative.
                Do not decide the case. Do not say the transaction is definitely fraud. Do not ask for external data.
                Use only the supplied JSON case data.

                Return only valid compact JSON with this exact shape:
                {
                  "overview": "one sentence case summary",
                  "keyObservations": ["observation 1", "observation 2", "observation 3"],
                  "recommendedActionRationale": "why the suggested next action is appropriate",
                  "uncertainty": "what remains uncertain"
                }

                Case data:
                %s
                """.formatted(objectMapper.writeValueAsString(caseData));
    }

    private record OllamaGenerateRequest(
            String model,
            String prompt,
            boolean stream,
            String format,
            Map<String, Object> options
    ) {
    }

    private record OllamaGenerateResponse(String response) {
    }

    private record OllamaNarrativePayload(
            String overview,
            List<String> keyObservations,
            String recommendedActionRationale,
            String uncertainty
    ) {
    }
}
