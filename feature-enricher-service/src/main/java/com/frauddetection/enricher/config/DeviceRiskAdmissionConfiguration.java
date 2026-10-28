package com.frauddetection.enricher.config;

import com.frauddetection.enricher.device.DisabledDeviceRiskAdmissionAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceRiskAdmissionProperties.class)
public class DeviceRiskAdmissionConfiguration {
    @Bean
    DeviceRiskAdmissionConfigurationValidator deviceRiskAdmissionConfigurationValidator(
            DeviceRiskAdmissionProperties properties) {
        return new DeviceRiskAdmissionConfigurationValidator(properties);
    }

    @Bean
    DisabledDeviceRiskAdmissionAdapter disabledDeviceRiskAdmissionAdapter() {
        return new DisabledDeviceRiskAdmissionAdapter();
    }
}
