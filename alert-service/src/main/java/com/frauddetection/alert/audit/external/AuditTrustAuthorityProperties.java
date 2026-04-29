package com.frauddetection.alert.audit.external;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.audit.trust-authority")
public class AuditTrustAuthorityProperties {

    private boolean enabled;
    private String url = "http://localhost:8095";
    private String hmacSecret;
    private boolean signingRequired;
    private String identityMode = "hmac-local";
    private String callerServiceName = "alert-service";
    private String callerEnvironment = "local";
    private String callerInstanceId;
    private JwtIdentity jwtIdentity = new JwtIdentity();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public boolean isSigningRequired() {
        return signingRequired;
    }

    public void setSigningRequired(boolean signingRequired) {
        this.signingRequired = signingRequired;
    }

    public String getIdentityMode() {
        return identityMode;
    }

    public void setIdentityMode(String identityMode) {
        this.identityMode = identityMode;
    }

    public String getCallerServiceName() {
        return callerServiceName;
    }

    public void setCallerServiceName(String callerServiceName) {
        this.callerServiceName = callerServiceName;
    }

    public String getCallerEnvironment() {
        return callerEnvironment;
    }

    public void setCallerEnvironment(String callerEnvironment) {
        this.callerEnvironment = callerEnvironment;
    }

    public String getCallerInstanceId() {
        return callerInstanceId;
    }

    public void setCallerInstanceId(String callerInstanceId) {
        this.callerInstanceId = callerInstanceId;
    }

    public JwtIdentity getJwtIdentity() {
        return jwtIdentity;
    }

    public void setJwtIdentity(JwtIdentity jwtIdentity) {
        this.jwtIdentity = jwtIdentity == null ? new JwtIdentity() : jwtIdentity;
    }

    public static class JwtIdentity {
        private String issuer;
        private String audience;
        private String keyId;
        private String privateKey;
        private String privateKeyPath;
        private Duration ttl = Duration.ofMinutes(5);
        private String authorities = "AUDIT_ANCHOR AUDIT_INTEGRITY";

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public String getAuthorities() {
            return authorities;
        }

        public void setAuthorities(String authorities) {
            this.authorities = authorities;
        }
    }
}
