package com.frauddetection.scoring.config;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class InternalServiceAuthHeaders {

    private static final String SERVICE_NAME_HEADER = "X-Internal-Service-Name";
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final InternalServiceClientProperties properties;
    private final InternalServiceCredentialProvider credentialProvider;

    public InternalServiceAuthHeaders(InternalServiceClientProperties properties) {
        this.properties = properties;
        this.credentialProvider = new InternalServiceCredentialProvider(properties);
    }

    public void apply(HttpHeaders headers) {
        if (!properties.enabled()) {
            return;
        }
        if ("TOKEN_VALIDATOR".equals(properties.normalizedMode())) {
            headers.set(SERVICE_NAME_HEADER, properties.normalizedServiceName());
            headers.set(SERVICE_TOKEN_HEADER, properties.normalizedToken());
            return;
        }
        if ("JWT_SERVICE_IDENTITY".equals(properties.normalizedMode())) {
            headers.setBearerAuth(credentialProvider.bearerToken());
        }
    }
}
