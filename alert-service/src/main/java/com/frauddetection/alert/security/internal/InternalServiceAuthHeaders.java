package com.frauddetection.alert.security.internal;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class InternalServiceAuthHeaders {

    private static final String SERVICE_NAME_HEADER = "X-Internal-Service-Name";
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final InternalServiceClientProperties properties;

    public InternalServiceAuthHeaders(InternalServiceClientProperties properties) {
        this.properties = properties;
    }

    public void apply(HttpHeaders headers) {
        if (!properties.enabled()) {
            return;
        }
        headers.set(SERVICE_NAME_HEADER, properties.normalizedServiceName());
        headers.set(SERVICE_TOKEN_HEADER, properties.normalizedToken());
    }
}
