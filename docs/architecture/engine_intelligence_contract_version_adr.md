# Engine Intelligence Contract Version Decision

Status: current FDP-129 decision.

FDP-129 keeps `EngineIntelligenceSummary.contractVersion=1` while adding optional `velocity.primary` because the
repository evidence is an atomic internal additive migration: common-events, fraud-scoring emission, alert-service
projection/read/API DTOs, OpenAPI, and Analyst Console validators all carry the same bounded three-engine contract.
There is no supported in-repository consumer that requires a strict two-engine maximum for current v1 payloads.

Version 1 now permits one or two engines when optional diagnostic engines are disabled, and up to three engines in the
canonical order `rules.primary`, `ml.python.primary`, `velocity.primary`. Duplicate `engineId`, wrong engineId/type
pairs, unknown engine IDs, four-engine payloads, and out-of-order engine arrays are invalid. A future fourth engine or
semantic change to the Velocity v1 observation window requires a versioned contract review.

This is not compatibility-by-dropping. Consumers must not hide Velocity, map it to Rules or ML, or fabricate a v1 shape
from a richer future payload.
