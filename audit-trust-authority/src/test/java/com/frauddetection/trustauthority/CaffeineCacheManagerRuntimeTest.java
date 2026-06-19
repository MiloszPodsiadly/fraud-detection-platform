package com.frauddetection.trustauthority;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineCacheManagerRuntimeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
            .withUserConfiguration(CacheRuntimeConfiguration.class)
            .withPropertyValues("spring.cache.type=caffeine");

    @Test
    void caffeineCacheManagerBeanIsAvailableAtRuntime() {
        assertThat(AuditTrustAuthorityApplication.class).hasAnnotation(EnableCaching.class);
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class CacheRuntimeConfiguration {
    }
}
