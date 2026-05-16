# CI Consolidation Plan

This plan was written before CI consolidation and then updated after implementation. It preserves branch-specific evidence instead of turning checks into advisory comments.

## Consolidation candidate: FDP-50 through FDP-53 frontend architecture gates

Old jobs:
- `FDP-50 Frontend API Client Runtime Boundary`
- `FDP-51 Analyst Console Workspace Runtime`
- `FDP-52 Workspace Split Detail UX`
- `FDP-53 Workspace Runtime Ownership`

Proposed new job:
- `Analyst Console Frontend Architecture Gate`

Why consolidate:
- The four jobs repeat checkout, Node setup, dependency install, UI build, API boundary guards, scope guards, skip/focus guards, and report verification.

Still proves:
- FDP-50 API client boundary tests and guards.
- FDP-51 runtime provider tests and scope guard.
- FDP-52 detail UX tests and scope guard.
- FDP-53 runtime ownership tests and scope guard.
- Full frontend build.

No longer proves:
- Nothing intentionally removed. Failure output is grouped by FDP section in the consolidated job.

Replacement tests/scripts:
- `npm test -- --run ... --outputFile=./test-results/fdp50-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/fdp51-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/fdp52-vitest.xml`
- `npm test -- --run ... --outputFile=./test-results/fdp53-vitest.xml`
- `npm run check:api-client-boundary:fdp50`
- `npm run check:scope:fdp50`
- `npm run check:scope:fdp51`
- `npm run check:scope:fdp52`
- `npm run check:fdp53-scope`

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
- FDP-50 through FDP-53 frontend jobs.

Proposed new job:
- `Analyst Console Frontend Architecture Gate`

Why consolidate:
- One dependency install and one build are enough when each FDP section still runs its own tests and JUnit output.

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
- FDP-50 through FDP-53 skip/focus guard steps.

Proposed new job:
- One union skip/focus guard in `Analyst Console Frontend Architecture Gate`.

Why consolidate:
- The same grep rule was repeated with overlapping test file lists.

Still proves:
- Required FDP-50 through FDP-53 test files do not contain `.skip` or `.only`.

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
- `scripts/check-fdp50-scope.mjs`
- `scripts/check-fdp51-scope.mjs`
- `scripts/check-fdp52-scope.mjs`
- `scripts/check-fdp53-scope.mjs`

Proposed new job:
- Keep branch-specific scripts, extract only generic helpers to `scripts/fdp-scope/scopeGuardHelpers.mjs`.

Why consolidate:
- Changed-file lookup, prefix matching, diff execution, and common source scanners were duplicated.

Still proves:
- Branch-specific scripts remain explicit and are still invoked.

No longer proves:
- Nothing removed.

Replacement tests/scripts:
- `scripts/check-fdp-scope-helpers-smoke.mjs`
- `scripts/check-fdp52-scope.mjs` uses shared helpers.

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
- `scripts/check-fdp-scope-helpers-smoke.mjs`

Risk:
- low

Decision:
- implemented

## Jobs Kept Separate

- Backend mutation and fraud-case proof gates stay separate because they protect backend behavior and transaction semantics.
- FDP-49 security route boundary stays separate because route ownership failures need direct security output.
- Release governance gates stay separate because they have different ownership and evidence meaning.
- Docker compose build stays separate because it proves buildability of compose images after upstream gates pass.
