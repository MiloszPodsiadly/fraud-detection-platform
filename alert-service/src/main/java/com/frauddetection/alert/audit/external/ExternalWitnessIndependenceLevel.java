package com.frauddetection.alert.audit.external;

enum ExternalWitnessIndependenceLevel {
    NONE,
    LOCAL_DEV_ONLY,
    SAME_BOUNDARY,
    SEPARATE_ACCOUNT,
    CROSS_ORG
}
