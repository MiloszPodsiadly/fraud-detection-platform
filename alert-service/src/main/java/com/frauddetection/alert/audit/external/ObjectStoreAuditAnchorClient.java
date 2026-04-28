package com.frauddetection.alert.audit.external;

import java.util.List;
import java.util.Optional;

interface ObjectStoreAuditAnchorClient {

    Optional<byte[]> getObject(String bucket, String key);

    void putObjectIfAbsent(String bucket, String key, byte[] content);

    List<String> listKeys(String bucket, String keyPrefix, int limit);

    default ExternalImmutabilityLevel immutabilityLevel(String bucket, String keyPrefix) {
        return ExternalImmutabilityLevel.NONE;
    }
}
