package com.frauddetection.alert.audit.external;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuditEvidenceExportAbuseDetector {

    private static final int MAX_ACTORS = 10_000;

    private final AlertServiceMetrics metrics;
    private final Map<String, String> lastFingerprintByActor = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ACTORS;
        }
    };

    public AuditEvidenceExportAbuseDetector(AlertServiceMetrics metrics) {
        this.metrics = metrics;
    }

    void record(String actorId, String exportFingerprint) {
        if (!StringUtils.hasText(exportFingerprint)) {
            return;
        }
        String key = StringUtils.hasText(actorId) ? actorId.trim() : "unknown";
        synchronized (lastFingerprintByActor) {
            String previous = lastFingerprintByActor.put(key, exportFingerprint);
            if (exportFingerprint.equals(previous)) {
                metrics.recordEvidenceExportRepeatedFingerprint();
            }
        }
    }
}
