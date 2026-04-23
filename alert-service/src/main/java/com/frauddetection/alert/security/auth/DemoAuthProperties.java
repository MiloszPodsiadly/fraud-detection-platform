package com.frauddetection.alert.security.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.demo-auth")
public record DemoAuthProperties(boolean enabled) {
}
