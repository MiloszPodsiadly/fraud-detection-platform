package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
import java.time.Duration;
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
            AlertServiceMetrics metrics,
            Environment environment,
            @Value("${app.audit.external-anchoring.publication.enabled:${app.audit.external-anchoring.enabled:false}}") boolean publicationEnabled,
            @Value("${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}") boolean publicationRequired,
            @Value("${app.audit.external-anchoring.publication.fail-closed:${app.audit.external-anchoring.publication.required:${app.audit.external-anchoring.enabled:false}}}") boolean publicationFailClosed,
            @Value("${app.audit.external-anchoring.sink:disabled}") String sink,
            @Value("${app.audit.external-anchoring.local-file.path:./target/audit-external-anchors.jsonl}") String localFilePath,
            @Value("${app.audit.external-anchoring.allow-local-file-in-prod:false}") boolean allowLocalFileInProd,
            @Value("${app.audit.external-anchoring.object-store.bucket:}") String objectStoreBucket,
            @Value("${app.audit.external-anchoring.object-store.prefix:}") String objectStorePrefix,
            @Value("${app.audit.external-anchoring.object-store.region:}") String objectStoreRegion,
            @Value("${app.audit.external-anchoring.object-store.endpoint:}") String objectStoreEndpoint,
            @Value("${app.audit.external-anchoring.object-store.access-key-id:}") String objectStoreAccessKeyId,
            @Value("${app.audit.external-anchoring.object-store.secret-access-key:}") String objectStoreSecretAccessKey,
            @Value("${app.audit.external-store.startup-validation:${app.audit.external-anchoring.object-store.startup-check-enabled:true}}") boolean objectStoreStartupCheckEnabled,
            @Value("${app.audit.external-anchoring.object-store.startup-test-write-enabled:false}") boolean objectStoreStartupTestWriteEnabled,
            @Value("${app.audit.external-anchoring.object-store.operation-timeout:2s}") Duration objectStoreOperationTimeout,
            @Value("${app.audit.external-anchoring.object-store.retry-backoff:100ms}") Duration objectStoreRetryBackoff,
            @Value("${app.audit.external-anchoring.object-store.max-attempts:2}") int objectStoreMaxAttempts,
            @Value("${app.audit.external-anchoring.policy.minimum-independence-level:SEPARATE_ACCOUNT}") String minimumIndependenceLevel,
            @Value("${app.audit.external-anchoring.policy.minimum-immutability-level:ENFORCED}") String minimumImmutabilityLevel,
            @Value("${app.audit.external-anchoring.policy.minimum-timestamp-trust-level:MEDIUM}") String minimumTimestampTrustLevel,
            @Value("${app.audit.external-anchoring.policy.require-delete-protection:true}") boolean requireDeleteProtection,
            @Value("${app.audit.external-anchoring.policy.require-overwrite-protection:true}") boolean requireOverwriteProtection,
            @Value("${app.audit.external-anchoring.policy.require-stable-reference:true}") boolean requireStableReference,
            @Value("${app.audit.external-anchoring.policy.allow-separate-account-in-prod:false}") boolean allowSeparateAccountInProd,
            @Value("${HOSTNAME:alert-service}") String instanceId
    ) {
        warnIfLegacyExternalAnchoringEnabled(environment);
        validatePublicationPolicy(publicationRequired, publicationFailClosed);
        boolean strictPublication = publicationRequired || publicationFailClosed;
        validateForbiddenPublisher(sink, publicationEnabled || strictPublication);
        if ("local-file".equals(sink)) {
            if (strictPublication) {
                throw new IllegalStateException("External anchoring requires a verified external witness; local-file is forbidden.");
            }
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
            ExternalImmutabilityLevel immutabilityLevel = validateObjectStoreReadiness(
                    client,
                    objectStoreBucket,
                    objectStorePrefix,
                    objectStoreStartupCheckEnabled,
                    publicationEnabled || strictPublication || objectStoreStartupTestWriteEnabled,
                    instanceId
            );
            ExternalWitnessCapabilities capabilities = client.capabilities(objectStoreBucket, trimSlashes(objectStorePrefix));
            validateExternalWitnessCapabilities(
                    capabilities,
                    strictPublication,
                    environment,
                    minimumIndependenceLevel,
                    minimumImmutabilityLevel,
                    minimumTimestampTrustLevel,
                    requireDeleteProtection,
                    requireOverwriteProtection,
                    requireStableReference,
                    allowSeparateAccountInProd
            );
            log.info(
                    "External audit anchor sink configured: sink_type=object-store bucket={} prefix={} region_configured={} endpoint_configured={} immutability_level={}",
                    objectStoreBucket,
                    objectStorePrefix,
                    StringUtils.hasText(objectStoreRegion),
                    StringUtils.hasText(objectStoreEndpoint),
                    immutabilityLevel
            );
            return new ObjectStoreExternalAuditAnchorSink(
                    objectStoreBucket,
                    objectStorePrefix,
                    client,
                    objectMapper,
                    metrics,
                    objectStoreOperationTimeout,
                    objectStoreRetryBackoff,
                    objectStoreMaxAttempts,
                    immutabilityLevel,
                    capabilities
            );
        }
        if ("external-object-store".equals(sink)) {
            throw new IllegalStateException("Use app.audit.external-anchoring.sink=object-store for FDP-22 object-store anchoring.");
        }
        if (strictPublication) {
            throw new IllegalStateException("External anchoring is enabled but no verified external witness publisher is configured.");
        }
        log.info("External audit anchor sink configured: sink_type=disabled");
        return new DisabledExternalAuditAnchorSink();
    }

    ExternalAuditAnchorSink externalAuditAnchorSink(
            ObjectMapper objectMapper,
            ObjectProvider<ObjectStoreAuditAnchorClient> objectStoreClient,
            AlertServiceMetrics metrics,
            Environment environment,
            boolean externalAnchoringEnabled,
            String sink,
            String localFilePath,
            boolean allowLocalFileInProd,
            String objectStoreBucket,
            String objectStorePrefix,
            String objectStoreRegion,
            String objectStoreEndpoint,
            String objectStoreAccessKeyId,
            String objectStoreSecretAccessKey,
            boolean objectStoreStartupCheckEnabled,
            boolean objectStoreStartupTestWriteEnabled,
            Duration objectStoreOperationTimeout,
            Duration objectStoreRetryBackoff,
            int objectStoreMaxAttempts,
            String instanceId
    ) {
        return externalAuditAnchorSink(
                objectMapper,
                objectStoreClient,
                metrics,
                environment,
                externalAnchoringEnabled,
                externalAnchoringEnabled,
                externalAnchoringEnabled,
                sink,
                localFilePath,
                allowLocalFileInProd,
                objectStoreBucket,
                objectStorePrefix,
                objectStoreRegion,
                objectStoreEndpoint,
                objectStoreAccessKeyId,
                objectStoreSecretAccessKey,
                objectStoreStartupCheckEnabled,
                objectStoreStartupTestWriteEnabled,
                objectStoreOperationTimeout,
                objectStoreRetryBackoff,
                objectStoreMaxAttempts,
                "SEPARATE_ACCOUNT",
                "ENFORCED",
                "MEDIUM",
                true,
                true,
                true,
                false,
                instanceId
        );
    }

    ExternalAuditAnchorSink externalAuditAnchorSink(
            ObjectMapper objectMapper,
            ObjectProvider<ObjectStoreAuditAnchorClient> objectStoreClient,
            AlertServiceMetrics metrics,
            Environment environment,
            boolean publicationEnabled,
            boolean publicationRequired,
            boolean publicationFailClosed,
            String sink,
            String localFilePath,
            boolean allowLocalFileInProd,
            String objectStoreBucket,
            String objectStorePrefix,
            String objectStoreRegion,
            String objectStoreEndpoint,
            String objectStoreAccessKeyId,
            String objectStoreSecretAccessKey,
            boolean objectStoreStartupCheckEnabled,
            boolean objectStoreStartupTestWriteEnabled,
            Duration objectStoreOperationTimeout,
            Duration objectStoreRetryBackoff,
            int objectStoreMaxAttempts,
            String instanceId
    ) {
        return externalAuditAnchorSink(
                objectMapper,
                objectStoreClient,
                metrics,
                environment,
                publicationEnabled,
                publicationRequired,
                publicationFailClosed,
                sink,
                localFilePath,
                allowLocalFileInProd,
                objectStoreBucket,
                objectStorePrefix,
                objectStoreRegion,
                objectStoreEndpoint,
                objectStoreAccessKeyId,
                objectStoreSecretAccessKey,
                objectStoreStartupCheckEnabled,
                objectStoreStartupTestWriteEnabled,
                objectStoreOperationTimeout,
                objectStoreRetryBackoff,
                objectStoreMaxAttempts,
                "SEPARATE_ACCOUNT",
                "ENFORCED",
                "MEDIUM",
                true,
                true,
                true,
                false,
                instanceId
        );
    }

    private void validatePublicationPolicy(boolean publicationRequired, boolean publicationFailClosed) {
        if (publicationFailClosed && !publicationRequired) {
            throw new IllegalStateException("app.audit.external-anchoring.publication.fail-closed=true requires publication.required=true.");
        }
    }

    private void warnIfLegacyExternalAnchoringEnabled(Environment environment) {
        if (environment == null) {
            return;
        }
        boolean legacyEnabled = "true".equalsIgnoreCase(environment.getProperty("AUDIT_EXTERNAL_ANCHORING_ENABLED"))
                || "true".equalsIgnoreCase(environment.getProperty("app.audit.external-anchoring.enabled"));
        if (legacyEnabled) {
            log.warn("app.audit.external-anchoring.enabled is deprecated; use app.audit.external-anchoring.publication.enabled, publication.required, and publication.fail-closed.");
        }
    }

    private void validateForbiddenPublisher(String sink, boolean externalAnchoringEnabled) {
        if (!externalAnchoringEnabled) {
            return;
        }
        if ("disabled".equals(sink)
                || "noop".equals(sink)
                || "local-file".equals(sink)
                || "in-memory".equals(sink)
                || "same-database".equals(sink)) {
            throw new IllegalStateException("External anchoring forbids fake publishers: " + sink + ".");
        }
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

    private ExternalImmutabilityLevel validateObjectStoreReadiness(
            ObjectStoreAuditAnchorClient client,
            String bucket,
            String prefix,
            boolean startupCheckEnabled,
            boolean startupTestWriteEnabled,
            String instanceId
    ) {
        ExternalImmutabilityLevel immutabilityLevel;
        try {
            immutabilityLevel = client.immutabilityLevel(bucket, trimSlashes(prefix));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Object-store external audit anchor immutability check failed.");
        }
        if (immutabilityLevel == null) {
            immutabilityLevel = ExternalImmutabilityLevel.NONE;
        }
        if (!startupCheckEnabled) {
            return immutabilityLevel;
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
        return immutabilityLevel;
    }

    private void validateExternalWitnessCapabilities(
            ExternalWitnessCapabilities capabilities,
            boolean externalAnchoringEnabled,
            Environment environment,
            String minimumIndependenceLevel,
            String minimumImmutabilityLevel,
            String minimumTimestampTrustLevel,
            boolean requireDeleteProtection,
            boolean requireOverwriteProtection,
            boolean requireStableReference,
            boolean allowSeparateAccountInProd
    ) {
        if (!externalAnchoringEnabled) {
            return;
        }
        if (capabilities == null
                || fakeWitnessType(capabilities.witnessType())
                || lowerThan(capabilities.independenceLevel(), minimumIndependenceLevel, ExternalWitnessIndependenceLevel.class)
                || lowerThan(capabilities.immutabilityLevel().name(), minimumImmutabilityLevel, ExternalImmutabilityLevel.class)
                || capabilities.timestampType() == ExternalWitnessTimestampType.APP_OBSERVED
                || lowerThan(capabilities.timestampTrustLevel(), minimumTimestampTrustLevel, ExternalTimestampTrustLevel.class)
                || !capabilities.supportsReadAfterWrite()
                || (requireStableReference && !capabilities.supportsStableReference())
                || (requireOverwriteProtection && !capabilities.supportsWriteOnce())
                || (requireDeleteProtection && !capabilities.supportsDeleteDenialOrRetention())) {
            throw new IllegalStateException("External anchoring witness capabilities are not verified.");
        }
        if (isProdLike(environment)
                && lowerThan(capabilities.independenceLevel(), ExternalWitnessIndependenceLevel.CROSS_ORG.name(), ExternalWitnessIndependenceLevel.class)
                && !allowSeparateAccountInProd) {
            throw new IllegalStateException("Prod-like external anchoring requires CROSS_ORG witness independence or an explicit SEPARATE_ACCOUNT limitation override.");
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

    private boolean fakeWitnessType(String witnessType) {
        String normalized = witnessType == null ? "" : witnessType.trim().toUpperCase();
        return normalized.isBlank()
                || "LOCAL_FILE".equals(normalized)
                || "IN_MEMORY".equals(normalized)
                || "SAME_DATABASE".equals(normalized)
                || "NOOP".equals(normalized)
                || "TEST_FIXTURE".equals(normalized)
                || "DISABLED".equals(normalized);
    }

    private boolean isProdLike(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(PROD_LIKE_PROFILES::contains);
    }

    private <E extends Enum<E>> boolean lowerThan(String actual, String minimum, Class<E> enumType) {
        E actualValue = enumValue(actual, enumType);
        E minimumValue = enumValue(minimum, enumType);
        return actualValue.ordinal() < minimumValue.ordinal();
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> enumType) {
        if (!StringUtils.hasText(value)) {
            return enumType.getEnumConstants()[0];
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return enumType.getEnumConstants()[0];
        }
    }
}
