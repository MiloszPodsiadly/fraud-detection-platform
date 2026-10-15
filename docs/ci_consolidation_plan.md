# CI Consolidation Plan

This plan was written before CI consolidation and then updated after implementation. It preserves branch-specific evidence instead of turning checks into advisory comments.

## Consolidation candidate: frontend architecture gates

Old jobs:
- `Frontend API Client Runtime Boundary`
- `Analyst Console Workspace Runtime`
- `Analyst Workspace Detail UX`
- `Workspace Runtime Ownership`

Proposed new job:
- `Analyst Console Frontend Architecture Gate`

Why consolidate:
- The four jobs repeat checkout, Node setup, dependency install, UI build, API boundary guards, scope guards, skip/focus guards, and report verification.

Still proves:
- API client boundary tests and guards.
- Workspace runtime provider tests and scope guard.
- Analyst workspace detail UX tests and scope guard.
- Workspace runtime ownership tests and scope guard.
- Full frontend build.

No longer proves:
- Nothing intentionally removed. Failure output is grouped by business-owned section in the consolidated job.

Replacement tests/scripts:
- `npm test -- --run ... --outputFile=./test-results/api-client-boundary-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/workspace-runtime-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/workspace-detail-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/workspace-runtime-ownership-vitest.xml`
- `npm run check:workspace-api-client-boundary`
- `npm run check:scope:api-client`
- `npm run check:scope:workspace-runtime`
- `npm run check:scope:workspace-detail`
- `npm run check:scope:workspace-runtime-ownership`

Risk:
- medium

Decision:
- implemented

## Consolidation candidate: FDP-46 analyst console product proof

Old jobs:
- `FDP-46 Fraud Case Work Queue UI`
- `FDP-46 Scored Transaction Filtering`
- `FDP-47 Analyst Console UX and Summary`

Proposed new job:
- `Analyst Console Product Gate`

Why consolidate:
- The old FDP-46 UI job repeated the general frontend install/test/build baseline, and the FDP-46 backend filtering job shared reviewer ownership with the FDP-47 analyst console product proof.

Still proves:
- FDP-46 frontend tests remain covered by `Frontend Build` and the product-gate skip guard.
- FDP-46 backend scored-transaction filtering/audit tests run and their JUnit reports are verified.
- FDP-47 backend summary tests, frontend tests, and UI build still run.

No longer proves:
- Separate job-level timing for FDP-46 UI and FDP-46 backend filtering.

Replacement tests/scripts:
- `Frontend Build`
- `Analyst Console Product Gate`
- `scripts/ci/verify-junit-reports.mjs`

Risk:
- medium

Decision:
- implemented

## Consolidation candidate: repeated frontend build/test/report verification steps

Old jobs:
- The frontend architecture jobs.

Proposed new job:
- `Analyst Console Frontend Architecture Gate`

Why consolidate:
- One dependency install and one build are enough when each business section still runs its own tests and JUnit output.

Still proves:
- Each required test file is present in the matching JUnit report.
- Build still runs after architecture tests.

No longer proves:
- Separate job-level timing for each FDP branch.

Replacement tests/scripts:
- Consolidated report verification step in `.github/workflows/ci.yml`.

Risk:
- low

Decision:
- implemented

## Consolidation candidate: repeated skip/only test guard steps

Old jobs:
- Frontend architecture skip/focus guard steps.

Proposed new job:
- One union skip/focus guard in `Analyst Console Frontend Architecture Gate`.

Why consolidate:
- The same grep rule was repeated with overlapping test file lists.

Still proves:
- Required frontend architecture test files do not contain `.skip` or `.only`.

No longer proves:
- Separate job-local skip guard output per FDP.

Replacement tests/scripts:
- `Ensure frontend architecture tests are not skipped or focused`.

Risk:
- low

Decision:
- implemented

## Consolidation candidate: repeated scope guard helpers

Old jobs:
- `scripts/check-api-client-scope.mjs`
- `scripts/check-workspace-runtime-scope.mjs`
- `scripts/check-workspace-detail-scope.mjs`
- `scripts/check-workspace-runtime-ownership-scope.mjs`

Proposed new job:
- Keep externally coupled branch-specific scripts, and place generic helpers in `scripts/scope-guards/scopeGuardHelpers.mjs`.

Why consolidate:
- Changed-file lookup, prefix matching, diff execution, and common source scanners were duplicated.

Still proves:
- Branch-specific scripts remain explicit and are still invoked.

No longer proves:
- Nothing removed.

Replacement tests/scripts:
- `scripts/check-scope-guard-helpers-smoke.mjs`
- `scripts/check-workspace-detail-scope.mjs` uses shared helpers.

Risk:
- low

Decision:
- implemented

## Consolidation candidate: documentation/scope governance jobs

Old jobs:
- No dedicated FDP-54 docs governance job existed.

Proposed new job:
- `FDP-54 CI Docs Governance`

Why consolidate:
- CI evidence comparison and documentation overclaim checks need one small, readable job.

Still proves:
- Docs do not contain unqualified high-risk claims.
- Evidence map and workflow job names do not drift.

No longer proves:
- Runtime behavior; this job is docs/tooling only.

Replacement tests/scripts:
- `scripts/check-doc-overclaims.mjs`
- `scripts/compare-ci-jobs.mjs`
- `scripts/check-scope-guard-helpers-smoke.mjs`

Risk:
- low

Decision:
- implemented

## Jobs Kept Separate

- Backend mutation and fraud-case proof gates stay separate because they protect backend behavior and transaction semantics.
- The security route boundary stays separate because route ownership failures need direct security output.
- Release governance gates stay separate because they have different ownership and evidence meaning.
- Docker compose build stays separate because it proves buildability of compose images after upstream gates pass.
