package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class MlGovernanceAdvisoryClient implements GovernanceAdvisoryClient {

    private static final int LOOKUP_LIMIT = 100;

    private final RestClient restClient;

    public MlGovernanceAdvisoryClient(GovernanceAuditProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.mlLookupTimeout());
        requestFactory.setReadTimeout(properties.mlLookupTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.mlGovernanceBaseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("Accept", "application/json");
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public GovernanceAdvisorySnapshot getAdvisory(String eventId) {
        try {
            AdvisoryListResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/governance/advisories")
                            .queryParam("limit", LOOKUP_LIMIT)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new GovernanceAdvisoryLookupUnavailableException();
                    })
                    .body(AdvisoryListResponse.class);
            return findEvent(response, eventId);
        } catch (RestClientException exception) {
            throw new GovernanceAdvisoryLookupUnavailableException();
        }
    }

    private GovernanceAdvisorySnapshot findEvent(AdvisoryListResponse response, String eventId) {
        if (response == null || response.advisoryEvents() == null) {
            throw new GovernanceAdvisoryLookupUnavailableException();
        }
        return response.advisoryEvents().stream()
                .filter(event -> eventId.equals(event.eventId()))
                .findFirst()
                .map(event -> new GovernanceAdvisorySnapshot(
                        event.eventId(),
                        event.modelName(),
                        event.modelVersion(),
                        event.severity(),
                        event.confidence(),
                        event.advisoryConfidenceContext()
                ))
                .orElseThrow(() -> new GovernanceAdvisoryNotFoundException(eventId));
    }

    private record AdvisoryListResponse(
            @JsonProperty("advisory_events")
            List<AdvisoryEvent> advisoryEvents
    ) {
    }

    private record AdvisoryEvent(
            @JsonProperty("event_id")
            String eventId,

            @JsonProperty("model_name")
            String modelName,

            @JsonProperty("model_version")
            String modelVersion,

            @JsonProperty("severity")
            String severity,

            @JsonProperty("confidence")
            String confidence,

            @JsonProperty("advisory_confidence_context")
            String advisoryConfidenceContext
    ) {
    }
}
