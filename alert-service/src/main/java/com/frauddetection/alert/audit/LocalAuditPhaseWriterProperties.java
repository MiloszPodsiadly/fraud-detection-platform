package com.frauddetection.alert.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.audit.local-phase-writer")
public class LocalAuditPhaseWriterProperties {

    private int maxAppendAttempts = 200;
    private long backoffMs = 5L;
    private long maxTotalWaitMs = 1_000L;
    private boolean allowLongTotalWait = false;

    public int getMaxAppendAttempts() {
        return maxAppendAttempts;
    }

    public void setMaxAppendAttempts(int maxAppendAttempts) {
        this.maxAppendAttempts = maxAppendAttempts;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }

    public long getMaxTotalWaitMs() {
        return maxTotalWaitMs;
    }

    public void setMaxTotalWaitMs(long maxTotalWaitMs) {
        this.maxTotalWaitMs = maxTotalWaitMs;
    }

    public boolean isAllowLongTotalWait() {
        return allowLongTotalWait;
    }

    public void setAllowLongTotalWait(boolean allowLongTotalWait) {
        this.allowLongTotalWait = allowLongTotalWait;
    }

    public boolean validForEvidenceGatedFinalize() {
        return maxAppendAttempts > 0
                && backoffMs > 0
                && maxTotalWaitMs > 0
                && (maxTotalWaitMs <= 5_000L || allowLongTotalWait);
    }
}
