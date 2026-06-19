package com.frauddetection.enricher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class FeatureEnricherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeatureEnricherServiceApplication.class, args);
    }
}
