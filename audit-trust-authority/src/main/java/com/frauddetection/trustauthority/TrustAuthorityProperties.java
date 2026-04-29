package com.frauddetection.trustauthority;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.trust-authority")
public class TrustAuthorityProperties {

    static final String DEFAULT_LOCAL_HMAC_SECRET = "local-dev-trust-hmac-secret";

    private String authorityName = "local-trust-authority";
    private String keyId = "local-ed25519-key-1";
    private String hmacSecret = DEFAULT_LOCAL_HMAC_SECRET;
    private String privateKeyPath;
    private String publicKeyPath;
    private List<KeyEntry> keys = new ArrayList<>();
    private boolean enabled = true;
    private boolean signingRequired = true;
    private String auditPath = "./target/trust-authority-audit.jsonl";
    private String identityMode = "hmac-local";
    private boolean allowLocalHmacInProd;
    private AuditProperties audit = new AuditProperties();
    private List<CallerEntry> callers = new ArrayList<>();

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

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSigningRequired() {
        return signingRequired;
    }

    public void setSigningRequired(boolean signingRequired) {
        this.signingRequired = signingRequired;
    }

    public String getAuditPath() {
        return auditPath;
    }

    public void setAuditPath(String auditPath) {
        this.auditPath = auditPath;
    }

    public String getIdentityMode() {
        return identityMode;
    }

    public void setIdentityMode(String identityMode) {
        this.identityMode = identityMode;
    }

    public boolean isAllowLocalHmacInProd() {
        return allowLocalHmacInProd;
    }

    public void setAllowLocalHmacInProd(boolean allowLocalHmacInProd) {
        this.allowLocalHmacInProd = allowLocalHmacInProd;
    }

    public AuditProperties getAudit() {
        return audit;
    }

    public void setAudit(AuditProperties audit) {
        this.audit = audit == null ? new AuditProperties() : audit;
    }

    public List<CallerEntry> getCallers() {
        return callers;
    }

    public void setCallers(List<CallerEntry> callers) {
        this.callers = callers == null ? new ArrayList<>() : callers;
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

    public static class AuditProperties {
        private String sink = "local-file";

        public String getSink() {
            return sink;
        }

        public void setSink(String sink) {
            this.sink = sink;
        }
    }

    public static class CallerEntry {
        private String serviceName;
        private String hmacSecret;
        private List<String> allowedPurposes = new ArrayList<>();
        private int signRateLimitPerMinute = 1000;

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public List<String> getAllowedPurposes() {
            return allowedPurposes;
        }

        public void setAllowedPurposes(List<String> allowedPurposes) {
            this.allowedPurposes = allowedPurposes == null ? new ArrayList<>() : allowedPurposes;
        }

        public int getSignRateLimitPerMinute() {
            return signRateLimitPerMinute;
        }

        public void setSignRateLimitPerMinute(int signRateLimitPerMinute) {
            this.signRateLimitPerMinute = signRateLimitPerMinute;
        }
    }
}
