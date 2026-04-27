package com.frauddetection.scoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Configuration
public class MlModelClientConfig {

    @Bean
    public RestClient mlModelRestClient(
            MlModelClientProperties properties,
            InternalServiceAuthHeaders internalAuthHeaders,
            InternalServiceClientProperties internalAuthProperties,
            RestClient.Builder builder
    ) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(InternalServiceClientRequestFactory.create(
                        URI.create(properties.baseUrl()),
                        properties.connectTimeout(),
                        properties.readTimeout(),
                        internalAuthProperties
                ))
                .requestInterceptor((request, body, execution) -> {
                    if ("MTLS_SERVICE_IDENTITY".equals(internalAuthProperties.normalizedMode())) {
                        request.getHeaders().set("Connection", "close");
                    }
                    internalAuthHeaders.apply(request.getHeaders());
                    return execution.execute(request, body);
                })
                .build();
    }
}
