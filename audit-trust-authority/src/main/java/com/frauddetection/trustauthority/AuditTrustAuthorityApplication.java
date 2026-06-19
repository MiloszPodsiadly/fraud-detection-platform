package com.frauddetection.trustauthority;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditTrustAuthorityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditTrustAuthorityApplication.class, args);
    }
}
