package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
class FileTrustAuthorityAuditSink implements TrustAuthorityAuditSink {

    private final TrustAuthorityProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    FileTrustAuthorityAuditSink(TrustAuthorityProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void append(TrustAuthorityAuditEvent event) {
        if (!StringUtils.hasText(properties.getAuditPath())) {
            throw new TrustAuthorityAuditException("Trust authority audit path is required.", null);
        }
        try {
            Path path = Path.of(properties.getAuditPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit event could not be serialized.", exception);
        } catch (IOException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted.", exception);
        }
    }
}
