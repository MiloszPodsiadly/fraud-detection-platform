package com.frauddetection.trustauthority;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.trust-authority")
public class TrustAuthorityProperties {

    static final String DEFAULT_LOCAL_TOKEN = "local-dev-trust-token";

    private String authorityName = "local-trust-authority";
    private String keyId = "local-ed25519-key-1";
    private String internalToken = DEFAULT_LOCAL_TOKEN;
    private String privateKeyPath;
    private String publicKeyPath;
    private List<KeyEntry> keys = new ArrayList<>();

    public String getAuthorityName() {
        return authorityName;
    }

    public void setAuthorityName(String authorityName) {
        this.authorityName = authorityName;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public List<KeyEntry> getKeys() {
        return keys;
    }

    public void setKeys(List<KeyEntry> keys) {
        this.keys = keys == null ? new ArrayList<>() : keys;
    }

    public static class KeyEntry {
        private String keyId;
        private String algorithm = "Ed25519";
        private String status = "ACTIVE";
        private String privateKey;
        private String publicKey;
        private String privateKeyPath;
        private String publicKeyPath;
        private Instant validFrom;
        private Instant validUntil;

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getPublicKeyPath() {
            return publicKeyPath;
        }

        public void setPublicKeyPath(String publicKeyPath) {
            this.publicKeyPath = publicKeyPath;
        }

        public Instant getValidFrom() {
            return validFrom;
        }

        public void setValidFrom(Instant validFrom) {
            this.validFrom = validFrom;
        }

        public Instant getValidUntil() {
            return validUntil;
        }

        public void setValidUntil(Instant validUntil) {
            this.validUntil = validUntil;
        }
    }
}
