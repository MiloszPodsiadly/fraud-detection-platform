package com.frauddetection.alert.regulated;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BankProfileIntegrationTest {

    @Test
    void bankProfileDeclaresFailClosedBankContract() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-bank.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("app.regulated-mutations.transaction-mode")).isEqualTo("REQUIRED");
        assertThat(properties.getProperty("app.trust-incidents.refresh-mode")).isEqualTo("ATOMIC");
        assertThat(properties.getProperty("app.outbox.publisher.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.outbox.recovery.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.outbox.confirmation.dual-control.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.audit.bank-mode.fail-closed")).isEqualTo("true");
        assertThat(properties.getProperty("app.sensitive-reads.audit.fail-closed")).isEqualTo("true");
    }
}
