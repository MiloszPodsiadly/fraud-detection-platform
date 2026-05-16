# FDP Evidence Status

Status: current evidence interpretation index.

## Scope

FDP documents are branch evidence. They can contain constraints, risks, and future work from the branch that
created them. Use this file to interpret those documents against the current repository state without rewriting
older branch records into current source-of-truth documents.

For current behavior, start with `docs/index.md`, `docs/architecture/index.md`, `docs/api/index.md`, and
`docs/ci_evidence_map.md`.

## Current Status By Range

| Range | Current status | Still valid | Later evidence | Still future scope |
| --- | --- | --- | --- | --- |
| FDP-25 | Branch evidence for regulated mutation foundation | Local evidence boundaries and non-claims | FDP-27 through FDP-40 regulated mutation hardening | External finality remains outside repo proof |
| FDP-27 | Branch evidence for merge-gate foundation | Transactional outbox and local commit wording | FDP-29 through FDP-40 proof and release governance | Distributed ACID and exactly-once Kafka remain non-claims |
| FDP-28/FDP-28B | Branch evidence for invariant proof and chaos handoff | Docker/Testcontainers proof requirement and chaos split | FDP-35 through FDP-38 chaos/readiness proof | Production-image live in-flight proof remains separate |
| FDP-29 | Implemented local evidence-gated finalize branch | Local finalize recovery and evidence-state limits | FDP-31 through FDP-40 hardening and release governance | External witnesses remain asynchronous/outside local transaction |
| FDP-30 | Implemented executor split | Executor responsibility separation | FDP-31 through FDP-34 lifecycle hardening | Broader mutation redesign remains out of scope |
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
| FDP-42 | Implemented fraud-case lifecycle branch | Local audited lifecycle workflow | FDP-43/FDP-44 idempotency hardening and FDP-45 read model | Regulated mutation finality remains out of scope |
| FDP-43/FDP-44 | Implemented idempotency hardening | Shared idempotency primitives and replay snapshot limits | FDP-45 through FDP-47 read/UI layers | Global exactly-once and lease fencing remain non-claims |
| FDP-45 | Implemented work queue read model | Bounded cursor, audit, OpenAPI, and read-model proof | FDP-46/FDP-47 analyst console UI layers | Lifecycle mutation UI remains out of scope |
| FDP-46/FDP-47 | Implemented analyst console product proof | Read-only work queue UI, scored filtering, summary contract | FDP-54 CI consolidation into `Analyst Console Product Gate` | Export, bulk action, assignment, and snapshot consistency remain out of scope |
| FDP-48/FDP-49 | Implemented auth and route boundary hardening | BFF lifecycle, route ownership, matcher order | FDP-50 through FDP-53 frontend runtime architecture | Enterprise IAM remains deployment scope |
| FDP-50/FDP-53 | Implemented frontend architecture proof | API client boundary, runtime provider, detail UX, runtime ownership | FDP-54 CI consolidation into `Analyst Console Frontend Architecture Gate` | Backend auth enforcement and product workflow changes remain out of scope |
| FDP-54 | Current CI/docs simplification branch | Evidence map, branch index, reviewer checklist, CI consolidation | Future branch docs should use the template and central indexes | Product behavior changes remain out of scope |

## Navigation Rule

Use `docs/fdp/branch_index.md` to find branch-level evidence, then use `docs/ci_evidence_map.md` to identify the
current CI gate that proves it. Branch evidence should not be promoted into current-state claims; add a current
status note or central index entry instead.

## Review Rule

If a branch document conflicts with a current index, current architecture summary, or current API semantics
document, prefer the current repository document and preserve the branch file as trace evidence.
