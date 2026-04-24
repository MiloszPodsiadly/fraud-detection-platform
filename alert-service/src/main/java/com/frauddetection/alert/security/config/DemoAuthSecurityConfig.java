package com.frauddetection.alert.security.config;

import com.frauddetection.alert.security.auth.AnalystAuthenticationFactory;
import com.frauddetection.alert.security.auth.AnalystPrincipalResolver;
import com.frauddetection.alert.security.auth.DemoAuthFilter;
import com.frauddetection.alert.security.auth.DemoAuthProperties;
import com.frauddetection.alert.security.error.ApiAuthenticationEntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(DemoAuthProperties.class)
public class DemoAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoAuthSecurityConfig.class);

    @Bean
    @ConditionalOnExpression("${app.security.demo-auth.enabled:false} and !${app.security.jwt.enabled:false}")
    DemoAuthFilter demoAuthFilter(
            AnalystPrincipalResolver principalResolver,
            AnalystAuthenticationFactory authenticationFactory,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            Environment environment
    ) {
        if (!environment.matchesProfiles("local", "dev", "docker-local", "test")) {
            throw new BeanCreationException("Demo header authentication can only be enabled for local/dev/docker-local/test profiles.");
        }
        log.info("Development-only demo header authentication is enabled for alert-service.");
        return new DemoAuthFilter(principalResolver, authenticationFactory, authenticationEntryPoint);
    }
}
