package com.frauddetection.enricher.config;

import com.frauddetection.enricher.merchant.DisabledMerchantRiskAdmissionAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantRiskAdmissionProperties.class)
public class MerchantRiskAdmissionConfiguration {
    @Bean
    @Lazy(false)
    MerchantRiskAdmissionConfigurationValidator merchantRiskAdmissionConfigurationValidator(
            MerchantRiskAdmissionProperties properties) {
        return new MerchantRiskAdmissionConfigurationValidator(properties);
    }

    @Bean
    DisabledMerchantRiskAdmissionAdapter disabledMerchantRiskAdmissionAdapter() {
        return new DisabledMerchantRiskAdmissionAdapter();
    }
}
