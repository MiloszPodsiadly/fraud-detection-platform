package com.frauddetection.enricher.graph;

/** Local projection write outcomes; ambiguous acknowledgements remain visible. */
enum GraphAppendState {
    APPENDED,
    EXISTING,
    CONFLICT,
    FENCED,
    ACK_RECOVERED,
    PENDING,
    DENIED,
    UNAVAILABLE
}
