package com.frauddetection.alert.audit.external;

import java.util.List;
import java.util.Optional;

interface ObjectStoreAuditAnchorClient {

    Optional<byte[]> getObject(String bucket, String key);

    void putObjectIfAbsent(String bucket, String key, byte[] content);

    List<String> listKeys(String bucket, String keyPrefix, int limit);

    default ObjectStoreAuditAnchorKeyPage listKeysPage(
            String bucket,
            String keyPrefix,
            int limit,
            String continuationToken
    ) {
        if (continuationToken != null) {
            throw new ExternalAuditAnchorSinkException(
                    "HEAD_SCAN_PAGINATION_UNSUPPORTED",
                    "Object-store anchor listing pagination is not supported."
            );
        }
        List<String> keys = listKeys(bucket, keyPrefix, limit);
        if (keys.size() >= limit) {
            throw new ExternalAuditAnchorSinkException(
                    "HEAD_SCAN_PAGINATION_UNSUPPORTED",
                    "Object-store anchor listing may be truncated."
            );
        }
        return new ObjectStoreAuditAnchorKeyPage(keys, null);
    }

    default ExternalImmutabilityLevel immutabilityLevel(String bucket, String keyPrefix) {
        return ExternalImmutabilityLevel.NONE;
    }
}

record ObjectStoreAuditAnchorKeyPage(
        List<String> keys,
        String nextContinuationToken
) {
    ObjectStoreAuditAnchorKeyPage {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
