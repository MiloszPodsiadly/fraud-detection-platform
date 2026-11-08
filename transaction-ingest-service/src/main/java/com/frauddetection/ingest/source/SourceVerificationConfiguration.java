package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SourceVerificationProperties.class)
public class SourceVerificationConfiguration {
    @Bean
    public SourceSignatureVerifier sourceSignatureVerifier(SourceVerificationProperties properties) {
        return new SourceSignatureVerifier(properties, Clock.systemUTC());
    }
}
