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
        assertThat(properties.getProperty("app.audit.external-anchoring.publication.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.audit.external-anchoring.publication.required")).isEqualTo("true");
        assertThat(properties.getProperty("app.audit.external-anchoring.publication.fail-closed")).isEqualTo("true");
        assertThat(properties.getProperty("app.audit.external-anchoring.sink")).isEqualTo("${AUDIT_EXTERNAL_ANCHORING_SINK}");
        assertThat(properties.getProperty("app.audit.trust-authority.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("app.audit.trust-authority.signing-required")).isEqualTo("true");
        assertThat(properties.getProperty("app.security.jwt.required")).isEqualTo("true");
        assertThat(properties.getProperty("app.security.demo-auth.enabled")).isEqualTo("false");
    }

    @Test
    void bankLocalProfileDeclaresSmokeOnlyRelaxedEvidencePosture() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-bank-local.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("app.audit.bank-mode.fail-closed")).isEqualTo("false");
        assertThat(properties.getProperty("app.audit.external-anchoring.publication.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("app.audit.trust-authority.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("app.security.demo-auth.enabled")).isEqualTo("true");
    }
}
