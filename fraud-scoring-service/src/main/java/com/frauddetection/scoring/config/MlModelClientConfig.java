package com.frauddetection.scoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MlModelClientConfig {

    @Bean
    public RestClient mlModelRestClient(
            MlModelClientProperties properties,
            InternalServiceClientProperties internalAuth,
            RestClient.Builder builder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    if (internalAuth.enabled()) {
                        request.getHeaders().set("X-Internal-Service-Name", internalAuth.normalizedServiceName());
                        request.getHeaders().set("X-Internal-Service-Token", internalAuth.normalizedToken());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
