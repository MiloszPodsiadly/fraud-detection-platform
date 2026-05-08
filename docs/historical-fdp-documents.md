# Historical FDP Documents

Status: current historical documentation index.

## Scope

Older FDP documents are retained for traceability. They may contain risks, constraints, or future work that later
branches addressed. Do not rewrite them as if earlier uncertainty never existed.

Historical FDP document. Later FDP branches may supersede parts of this scope. See `docs/index.md` and
`docs/architecture/current-architecture.md` for current state.

## Current Status By Range

| Range | Current status | Still valid | Superseded by | Still future scope |
| --- | --- | --- | --- | --- |
| FDP-31 | Implemented branch evidence | Claim/replay policy extraction | FDP-32 through FDP-40 hardening | None in current documentation cleanup scope |
| FDP-32 | Implemented branch evidence | Lease-owner fencing docs | FDP-33 through FDP-40 operational proof | Production enablement remains separate |
| FDP-33 | Implemented branch evidence | Lease renewal operations | FDP-34 checkpoint adoption | Production enablement remains separate |
| FDP-34 | Implemented branch evidence | Safe checkpoint adoption wording | FDP-35 through FDP-38 proof docs | None in current documentation cleanup scope |
| FDP-35 | Implemented readiness proof | Readiness, not enablement | FDP-36/FDP-37/FDP-38 chaos proof split | Real external finality remains future |
| FDP-36 | Implemented real chaos proof scope | Real alert-service kill proof wording | FDP-37 production-image proof | Broader production certification |
| FDP-37 | Implemented production-image durable proof | Image digest/provenance evidence | FDP-38 live fixture proof | Live production-image instruction-boundary proof |
| FDP-38 | Implemented live fixture proof | Runtime-reached test-fixture claim | FDP-39 release governance separation | Production image live in-flight proof |
| FDP-39 | Implemented release artifact separation | No-overclaim release governance | FDP-40 platform controls readiness | External platform enforcement |
| FDP-40 | Implemented platform controls readiness | Single release owner and external controls | Current documentation cleanup | Actual production enablement |

## Review Rule

If a historical document conflicts with a current index, current architecture summary, or current API semantics
document, prefer the current repository document and preserve the historical file as trace evidence.


