package com.frauddetection.scoring.service;

record RulesV1SignalResolution(
        String reasonCode,
        PredicateResolution predicateResolution,
        RulesV1Contribution contribution
) {
    boolean contributes() {
        return contribution.weight() > 0.0d;
    }
}
