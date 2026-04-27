package com.frauddetection.alert.audit.trust;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.audit.trust-attestation")
public class AuditTrustAttestationProperties {

    private final Signing signing = new Signing();

    public Signing getSigning() {
        return signing;
    }

    public static class Signing {
        private String mode = "disabled";
        private String localDevKeyId = "local-dev-trust-attestation-key";
        private String localDevSecret = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getLocalDevKeyId() {
            return localDevKeyId;
        }

        public void setLocalDevKeyId(String localDevKeyId) {
            this.localDevKeyId = localDevKeyId;
        }

        public String getLocalDevSecret() {
            return localDevSecret;
        }

        public void setLocalDevSecret(String localDevSecret) {
            this.localDevSecret = localDevSecret;
        }
    }
}
