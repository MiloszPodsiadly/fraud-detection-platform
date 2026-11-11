package com.frauddetection.common.events.source;

/** Non-PII Mongo identity for the single active attested feature-history admission per customer. */
public final class SourceFeatureOrderIdentity {
    private SourceFeatureOrderIdentity() { }

    public static String identity(SourceScope scope, String customerId) {
        if (scope == null || customerId == null || customerId.isBlank() || customerId.length() > 128) {
            throw new IllegalArgumentException("SOURCE_FEATURE_ORDER_IDENTITY_INVALID");
        }
        return SourceReceiptIdentity.digest("source-feature-order-gate/v1", scope.issuer(), scope.institution(),
                scope.source(), scope.sourceEpoch(), customerId);
    }
}
