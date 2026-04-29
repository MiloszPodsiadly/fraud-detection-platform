package com.frauddetection.alert.audit.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.audit.trust-authority")
public class AuditTrustAuthorityProperties {

    private boolean enabled;
    private String url = "http://localhost:8095";
    private String hmacSecret;
    private boolean signingRequired;
    private String callerServiceName = "alert-service";
    private String callerEnvironment = "local";
    private String callerInstanceId;

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
}
