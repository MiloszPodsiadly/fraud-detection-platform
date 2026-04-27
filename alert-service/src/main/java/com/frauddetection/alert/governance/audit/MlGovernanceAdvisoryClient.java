package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.security.internal.InternalServiceAuthHeaders;
import com.frauddetection.alert.security.internal.InternalServiceClientProperties;
import com.frauddetection.alert.security.internal.InternalServiceClientRequestFactory;
import com.frauddetection.alert.security.internal.InternalMtlsClientHandshakeMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MlGovernanceAdvisoryClient implements GovernanceAdvisoryClient {

    private static final int LOOKUP_LIMIT = 100;

    private final RestClient restClient;

    public MlGovernanceAdvisoryClient(
            GovernanceAuditProperties properties,
            InternalServiceAuthHeaders internalAuthHeaders,
            InternalServiceClientProperties internalAuthProperties,
            MeterRegistry meterRegistry
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.mlGovernanceBaseUrl().toString())
                .requestFactory(InternalServiceClientRequestFactory.create(
                        properties.mlGovernanceBaseUrl(),
                        properties.mlLookupTimeout(),
                        properties.mlLookupTimeout(),
                        internalAuthProperties
                ))
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("Accept", "application/json");
                    if ("MTLS_SERVICE_IDENTITY".equals(internalAuthProperties.normalizedMode())) {
                        request.getHeaders().set("Connection", "close");
                    }
                    internalAuthHeaders.apply(request.getHeaders());
                    try {
                        return execution.execute(request, body);
                    } catch (RuntimeException exception) {
                        if ("MTLS_SERVICE_IDENTITY".equals(internalAuthProperties.normalizedMode())) {
                            InternalMtlsClientHandshakeMetrics.recordIfMtlsFailure(exception, meterRegistry);
                        }
                        throw exception;
                    }
                })
                .build();
    }

    @Override
    public GovernanceAdvisorySnapshot getAdvisory(String eventId) {
        GovernanceAdvisoryEvent event = getAdvisoryEvent(eventId);
        return new GovernanceAdvisorySnapshot(
                event.eventId(),
                event.modelName(),
                event.modelVersion(),
                event.severity(),
                event.confidence(),
                event.advisoryConfidenceContext()
        );
    }

    @Override
    public GovernanceAdvisoryEvent getAdvisoryEvent(String eventId) {
        return findEvent(listAdvisories(new GovernanceAdvisoryQuery(null, null, LOOKUP_LIMIT)), eventId);
    }

    @Override
    public GovernanceAdvisoryListResponse listAdvisories(GovernanceAdvisoryQuery query) {
        try {
            GovernanceAdvisoryListResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/governance/advisories")
                            .queryParam("limit", query.limit() == null ? LOOKUP_LIMIT : query.limit())
                            .queryParamIfPresent("severity", java.util.Optional.ofNullable(query.severity()))
                            .queryParamIfPresent("model_version", java.util.Optional.ofNullable(query.modelVersion()))
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new GovernanceAdvisoryLookupUnavailableException();
                    })
                    .body(GovernanceAdvisoryListResponse.class);
            if (response == null) {
                throw new GovernanceAdvisoryLookupUnavailableException();
            }
            return response;
        } catch (RestClientException exception) {
            throw new GovernanceAdvisoryLookupUnavailableException();
        }
    }

    private GovernanceAdvisoryEvent findEvent(GovernanceAdvisoryListResponse response, String eventId) {
        if (response == null || response.advisoryEvents() == null) {
            throw new GovernanceAdvisoryLookupUnavailableException();
        }
        return response.advisoryEvents().stream()
                .filter(event -> eventId.equals(event.eventId()))
                .findFirst()
                .orElseThrow(() -> new GovernanceAdvisoryNotFoundException(eventId));
    }
}
