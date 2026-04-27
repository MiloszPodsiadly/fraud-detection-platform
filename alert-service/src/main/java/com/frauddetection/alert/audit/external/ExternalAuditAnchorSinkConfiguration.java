package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
class ExternalAuditAnchorSinkConfiguration {

    @Bean
    ExternalAuditAnchorSink externalAuditAnchorSink(
            ObjectMapper objectMapper,
            @Value("${app.audit.external-anchoring.sink:disabled}") String sink,
            @Value("${app.audit.external-anchoring.local-file.path:./target/audit-external-anchors.jsonl}") String localFilePath
    ) {
        if ("local-file".equals(sink)) {
            return new LocalFileExternalAuditAnchorSink(Path.of(localFilePath), objectMapper);
        }
        return new DisabledExternalAuditAnchorSink();
    }
}
