package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

@Configuration
class ExternalAuditAnchorSinkConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuditAnchorSinkConfiguration.class);
    private static final Set<String> LOCAL_PROFILES = Set.of("local", "dev", "test", "docker-local");
    private static final Set<String> PROD_LIKE_PROFILES = Set.of("prod", "production", "staging");

    @Bean
    ExternalAuditAnchorSink externalAuditAnchorSink(
            ObjectMapper objectMapper,
            Environment environment,
            @Value("${app.audit.external-anchoring.sink:disabled}") String sink,
            @Value("${app.audit.external-anchoring.local-file.path:./target/audit-external-anchors.jsonl}") String localFilePath,
            @Value("${app.audit.external-anchoring.allow-local-file-in-prod:false}") boolean allowLocalFileInProd
    ) {
        if ("local-file".equals(sink)) {
            validateLocalFileSink(environment, allowLocalFileInProd);
            return new LocalFileExternalAuditAnchorSink(Path.of(localFilePath), objectMapper);
        }
        if ("external-object-store".equals(sink)) {
            throw new IllegalStateException("External object-store audit anchor sink is not implemented yet.");
        }
        return new DisabledExternalAuditAnchorSink();
    }

    private void validateLocalFileSink(Environment environment, boolean allowLocalFileInProd) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (!allowLocalFileInProd && profiles.stream().anyMatch(PROD_LIKE_PROFILES::contains)) {
            throw new IllegalStateException("Local-file external audit anchor sink is not allowed in prod-like profiles.");
        }
        if (profiles.stream().noneMatch(LOCAL_PROFILES::contains)) {
            log.warn("Local-file external audit anchor sink is for development verification only and is not production WORM storage.");
        }
    }
}
