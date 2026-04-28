package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Configuration
class ExternalAuditAnchorSinkConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuditAnchorSinkConfiguration.class);
    private static final Set<String> LOCAL_PROFILES = Set.of("local", "dev", "test", "docker-local");
    private static final Set<String> PROD_LIKE_PROFILES = Set.of("prod", "production", "staging");

    @Bean
    ExternalAuditAnchorSink externalAuditAnchorSink(
            ObjectMapper objectMapper,
            ObjectProvider<ObjectStoreAuditAnchorClient> objectStoreClient,
            Environment environment,
            @Value("${app.audit.external-anchoring.sink:disabled}") String sink,
            @Value("${app.audit.external-anchoring.local-file.path:./target/audit-external-anchors.jsonl}") String localFilePath,
            @Value("${app.audit.external-anchoring.allow-local-file-in-prod:false}") boolean allowLocalFileInProd,
            @Value("${app.audit.external-anchoring.object-store.bucket:}") String objectStoreBucket,
            @Value("${app.audit.external-anchoring.object-store.prefix:}") String objectStorePrefix,
            @Value("${app.audit.external-anchoring.object-store.region:}") String objectStoreRegion,
            @Value("${app.audit.external-anchoring.object-store.endpoint:}") String objectStoreEndpoint,
            @Value("${app.audit.external-anchoring.object-store.access-key-id:}") String objectStoreAccessKeyId,
            @Value("${app.audit.external-anchoring.object-store.secret-access-key:}") String objectStoreSecretAccessKey,
            @Value("${app.audit.external-anchoring.object-store.startup-check-enabled:true}") boolean objectStoreStartupCheckEnabled,
            @Value("${app.audit.external-anchoring.object-store.startup-test-write-enabled:false}") boolean objectStoreStartupTestWriteEnabled,
            @Value("${HOSTNAME:alert-service}") String instanceId
    ) {
        if ("local-file".equals(sink)) {
            validateLocalFileSink(environment, allowLocalFileInProd);
            log.info("External audit anchor sink configured: sink_type=local-file path={}", localFilePath);
            return new LocalFileExternalAuditAnchorSink(Path.of(localFilePath), objectMapper);
        }
        if ("object-store".equals(sink)) {
            validateObjectStoreSink(
                    objectStoreBucket,
                    objectStorePrefix,
                    objectStoreRegion,
                    objectStoreEndpoint,
                    objectStoreAccessKeyId,
                    objectStoreSecretAccessKey
            );
            ObjectStoreAuditAnchorClient client = objectStoreClient.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException("Object-store external audit anchor client is not configured.");
            }
            validateObjectStoreReadiness(
                    client,
                    objectStoreBucket,
                    objectStorePrefix,
                    objectStoreStartupCheckEnabled,
                    objectStoreStartupTestWriteEnabled,
                    instanceId
            );
            log.info(
                    "External audit anchor sink configured: sink_type=object-store bucket={} prefix={} region_configured={} endpoint_configured={}",
                    objectStoreBucket,
                    objectStorePrefix,
                    StringUtils.hasText(objectStoreRegion),
                    StringUtils.hasText(objectStoreEndpoint)
            );
            return new ObjectStoreExternalAuditAnchorSink(objectStoreBucket, objectStorePrefix, client, objectMapper);
        }
        if ("external-object-store".equals(sink)) {
            throw new IllegalStateException("Use app.audit.external-anchoring.sink=object-store for FDP-22 object-store anchoring.");
        }
        log.info("External audit anchor sink configured: sink_type=disabled");
        return new DisabledExternalAuditAnchorSink();
    }

    private void validateLocalFileSink(Environment environment, boolean allowLocalFileInProd) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (profiles.stream().anyMatch(PROD_LIKE_PROFILES::contains)) {
            throw new IllegalStateException("Local-file external audit anchor sink is not allowed in prod-like profiles.");
        }
        if (allowLocalFileInProd) {
            log.warn("Ignoring local-file external audit anchor prod override; local-file is never allowed in prod-like profiles.");
        }
        if (profiles.stream().noneMatch(LOCAL_PROFILES::contains)) {
            log.warn("Local-file external audit anchor sink is for development verification only and is not production WORM storage.");
        }
    }

    private void validateObjectStoreSink(
            String bucket,
            String prefix,
            String region,
            String endpoint,
            String accessKeyId,
            String secretAccessKey
    ) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("Object-store external audit anchor sink requires bucket.");
        }
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalStateException("Object-store external audit anchor sink requires prefix.");
        }
        if (!StringUtils.hasText(region) && !StringUtils.hasText(endpoint)) {
            throw new IllegalStateException("Object-store external audit anchor sink requires region or endpoint.");
        }
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(secretAccessKey)) {
            throw new IllegalStateException("Object-store external audit anchor sink requires credentials.");
        }
    }

    private void validateObjectStoreReadiness(
            ObjectStoreAuditAnchorClient client,
            String bucket,
            String prefix,
            boolean startupCheckEnabled,
            boolean startupTestWriteEnabled,
            String instanceId
    ) {
        if (!startupCheckEnabled) {
            return;
        }
        try {
            List<String> ignored = client.listKeys(bucket, trimSlashes(prefix), 1);
            if (ignored == null) {
                throw new IllegalStateException("Object-store external audit anchor startup check failed.");
            }
            if (startupTestWriteEnabled) {
                verifyStartupProbeWrite(client, bucket, trimSlashes(prefix), instanceId);
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Object-store external audit anchor startup check failed.");
        }
    }

    private void verifyStartupProbeWrite(
            ObjectStoreAuditAnchorClient client,
            String bucket,
            String prefix,
            String instanceId
    ) {
        String safeInstanceId = StringUtils.hasText(instanceId)
                ? instanceId.replaceAll("[^A-Za-z0-9._-]", "_")
                : "alert-service";
        String key = prefix + "/.healthcheck/" + safeInstanceId + ".json";
        byte[] content = ("{\"kind\":\"audit-anchor-startup-check\",\"schema_version\":\"1.0\",\"instance_id\":\""
                + safeInstanceId + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            client.putObjectIfAbsent(bucket, key, content);
        } catch (ExternalAuditAnchorSinkException exception) {
            if (!"CONFLICT".equals(exception.reason())) {
                throw exception;
            }
        }
        byte[] stored = client.getObject(bucket, key)
                .orElseThrow(() -> new IllegalStateException("Object-store external audit anchor startup test write was not readable."));
        if (!java.util.Arrays.equals(stored, content)) {
            throw new IllegalStateException("Object-store external audit anchor startup test write mismatch.");
        }
    }

    private String trimSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
