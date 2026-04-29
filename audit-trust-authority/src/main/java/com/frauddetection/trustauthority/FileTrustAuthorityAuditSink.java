package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "app.trust-authority.audit", name = "sink", havingValue = "local-file", matchIfMissing = true)
class FileTrustAuthorityAuditSink implements TrustAuthorityAuditSink {

    private final TrustAuthorityProperties properties;
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private long chainPosition;
    private String latestHash;

    FileTrustAuthorityAuditSink(TrustAuthorityProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void append(TrustAuthorityAuditEvent event) {
        if (!StringUtils.hasText(properties.getAuditPath())) {
            throw new TrustAuthorityAuditException("Trust authority audit path is required.", null);
        }
        try {
            long nextPosition = chainPosition + 1;
            TrustAuthorityAuditEvent chained = event.withChain(
                    latestHash,
                    TrustAuthorityAuditHasher.hash(event, latestHash, nextPosition),
                    nextPosition
            );
            Path path = Path.of(properties.getAuditPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String line = objectMapper.writeValueAsString(chained) + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            chainPosition = nextPosition;
            latestHash = chained.eventHash();
        } catch (JsonProcessingException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit event could not be serialized.", exception);
        } catch (IOException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit event could not be persisted.", exception);
        }
    }

    @Override
    public synchronized TrustAuthorityAuditIntegrityResponse integrity(int limit) {
        if (!StringUtils.hasText(properties.getAuditPath())) {
            return TrustAuthorityAuditIntegrityResponse.unavailable("AUDIT_PATH_MISSING");
        }
        Path path = Path.of(properties.getAuditPath());
        if (!Files.exists(path)) {
            return new TrustAuthorityAuditIntegrityResponse("VALID", 0, null, null, null, List.of());
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<TrustAuthorityAuditEvent> events = new ArrayList<>();
            for (String line : lines.stream().skip(Math.max(0, lines.size() - Math.max(1, limit))).toList()) {
                if (StringUtils.hasText(line)) {
                    events.add(objectMapper.readValue(line, TrustAuthorityAuditEvent.class));
                }
            }
            return TrustAuthorityAuditIntegrityVerifier.verify(events);
        } catch (IOException exception) {
            return TrustAuthorityAuditIntegrityResponse.unavailable("AUDIT_STORE_UNAVAILABLE");
        }
    }

    @Override
    public synchronized TrustAuthorityAuditHeadResponse head() {
        if (!StringUtils.hasText(properties.getAuditPath())) {
            throw new TrustAuthorityAuditException("Trust authority audit path is required.", null);
        }
        Path path = Path.of(properties.getAuditPath());
        if (!Files.exists(path)) {
            return TrustAuthorityAuditHeadResponse.empty();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = lines.size() - 1; index >= 0; index--) {
                String line = lines.get(index);
                if (StringUtils.hasText(line)) {
                    return TrustAuthorityAuditHeadResponse.from(objectMapper.readValue(line, TrustAuthorityAuditEvent.class));
                }
            }
            return TrustAuthorityAuditHeadResponse.empty();
        } catch (IOException exception) {
            throw new TrustAuthorityAuditException("Trust authority audit head could not be read.", exception);
        }
    }
}
