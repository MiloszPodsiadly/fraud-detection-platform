# CI Evidence Map

This map describes what each merge-blocking CI job proves and what it does not prove. It is evidence-oriented, not a claim that any single job covers security, runtime behavior, and deployment posture at once.

## Backend Maven Build

Purpose:
- Run the full Maven verify lifecycle for backend modules.

Proves:
- Backend unit and integration tests wired into Maven still pass.
- Backend modules compile together.

Does not prove:
- Frontend runtime behavior, Docker image behavior, or external platform controls.

Required for merge:
- Yes

Primary proof files:
- `pom.xml`
- Backend `src/test` suites executed by `mvn -B verify`

Related FDP branches:
- FDP-42 through FDP-53

Candidate for consolidation:
- No
- Rationale: This is the broad backend regression baseline and should stay diagnosable.

## FDP-42 Fraud Case Management

Purpose:
- Prove fraud-case lifecycle, audit, controller, and security behavior introduced around FDP-42.

Proves:
- Named FDP-42 backend test classes execute and produce Surefire XML.
- Fraud-case transition, mutation invariant, audit, controller, and security slices remain covered.

Does not prove:
- FDP-50 through FDP-53 frontend architecture behavior or Docker image behavior.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp42*Test.java`
- `alert-service/src/test/java/**/FraudCase*Test.java`
- `.github/workflows/ci.yml`

Related FDP branches:
- FDP-42

Candidate for consolidation:
- No
- Rationale: Backend lifecycle proof has different ownership from frontend architecture gates.

## FDP-43 Fraud Case Idempotency

Purpose:
- Prove fraud-case idempotency compatibility and failure behavior.

Proves:
- Canonical hashing, lifecycle idempotency, concurrency, failure, and transaction integration tests execute.

Does not prove:
- New frontend runtime boundaries, CI docs integrity, or production IAM hardening.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp43*Test.java`
- `alert-service/src/test/java/**/FraudCaseLifecycleIdempotency*Test.java`

Related FDP branches:
- FDP-43

Candidate for consolidation:
- No
- Rationale: It protects backend mutation semantics and should not be hidden in a frontend job.

## FDP-44 Fraud Case Idempotency Hardening

Purpose:
- Prove replay snapshot, retention, and operational idempotency hardening.

Proves:
- FDP-44 backend hardening tests and report verification execute.
- Alert metrics coverage tied to FDP-44 still runs.

Does not prove:
- UI stale-state behavior, BFF session lifecycle, or release governance.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp44*Test.java`
- `alert-service/src/test/java/**/AlertServiceMetricsTest.java`

Related FDP branches:
- FDP-44

Candidate for consolidation:
- No
- Rationale: Backend idempotency evidence has separate risk and failure output.

## FDP-45 Fraud Case Work Queue Read Model

Purpose:
- Prove the fraud-case work queue read model, cursor behavior, audit, and docs contract.

Proves:
- Read-model, cursor, filter, audit, legacy-route, OpenAPI, and docs tests execute.

Does not prove:
- Frontend workspace runtime ownership or Docker compose build behavior.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp45*Test.java`
- `docs/fdp/fdp_45_work_queue_readiness.md`

Related FDP branches:
- FDP-45

Candidate for consolidation:
- No
- Rationale: This is a large backend read-model proof suite.

## Python ML Checks

Purpose:
- Prove the Python ML service tests and model artifact loading.

Proves:
- ML unit tests pass and the trained model artifact can be loaded.

Does not prove:
- Backend authorization, frontend API boundaries, or Docker compose service integration.

Required for merge:
- Yes

Primary proof files:
- `ml-inference-service/tests`
- `ml-inference-service/app/models`

Related FDP branches:
- None

Candidate for consolidation:
- No
- Rationale: Different language/runtime and different failure mode.

## Frontend Build

Purpose:
- Prove baseline Analyst Console install, authority generation, tests, and build.

Proves:
- Frontend dependencies install, generated authority constants are current, Vitest baseline passes, and Vite builds.

Does not prove:
- Branch-specific FDP-50 through FDP-53 architecture gates or backend authorization.

Required for merge:
- Yes

Primary proof files:
- `analyst-console-ui/package.json`
- `analyst-console-ui/src`
- `scripts/generate-frontend-authorities.mjs`

Related FDP branches:
- FDP-46
- FDP-47
- FDP-48
- FDP-50
- FDP-51
- FDP-52
- FDP-53

Candidate for consolidation:
- No
- Rationale: It is the general frontend baseline; branch-specific proof remains explicit in the architecture gate.

## FDP-46 Fraud Case Work Queue UI

Replaced by:
- `Frontend Build`
- `Analyst Console Product Gate`

Purpose:
- Former standalone fraud-case work queue UI job.

Proves:
- Now proved by the required `Frontend Build` job, plus the FDP-46/FDP-47 skip guard and focused frontend tests inside `Analyst Console Product Gate`.

Does not prove:
- Backend read-model correctness or later workspace runtime boundaries.

Required for merge:
- Yes

Primary proof files:
- `analyst-console-ui/src/components/FraudCaseWorkQueuePanel.test.jsx`
- `analyst-console-ui/src/fraudCases/workQueueState.test.js`
- `analyst-console-ui/src/pages/AlertsListPage.test.jsx`

Related FDP branches:
- FDP-46

Candidate for consolidation:
- Yes
- Rationale: The full frontend test/build baseline already runs in `Frontend Build`; keeping a second standalone FDP-46 UI job repeated setup without adding stronger proof.

## FDP-46 Scored Transaction Filtering

Replaced by:
- `Analyst Console Product Gate`

Purpose:
- Former standalone backend scored-transaction filtering and audit job.

Proves:
- Now proved by the `Run FDP-46 backend search and audit tests` and `Verify FDP-46 backend test reports` sections of `Analyst Console Product Gate`.

Does not prove:
- Frontend filter UX or workspace runtime behavior.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp46*Test.java`

Related FDP branches:
- FDP-46

Candidate for consolidation:
- Yes
- Rationale: It shares ownership and merge meaning with the analyst console product proof, while staying separate from frontend architecture and release governance gates.

## FDP-28 Docker Integration Proof

Purpose:
- Prove Docker-backed integration tests for the earlier platform baseline.

Proves:
- FDP-28 Testcontainers-backed proof suite executes.

Does not prove:
- Compose production stack build, frontend architecture boundaries, or release governance.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp28*Test.java`
- `.github/workflows/ci.yml`

Related FDP branches:
- FDP-28

Candidate for consolidation:
- No
- Rationale: Docker-backed backend integration failures need direct visibility.

## FDP-35 Production Readiness Proof

Purpose:
- Prove the FDP-35 regulated mutation readiness suite.

Proves:
- FDP-35 backend proof tests run and reports are uploaded.

Does not prove:
- External deployment enforcement, enterprise IAM, or frontend runtime state.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp35*Test.java`
- `docs/testing/fdp_35_regulated_mutation_readiness_proof.md`

Related FDP branches:
- FDP-35

Candidate for consolidation:
- No
- Rationale: Regulated mutation readiness proof should stay isolated from UI and docs jobs.

## Analyst Console Product Gate

Purpose:
- Prove FDP-46 scored-transaction filtering/audit coverage and FDP-47 analyst console summary UX in one product-domain gate.

Proves:
- FDP-46 backend search/audit tests execute and JUnit reports contain required classes.
- FDP-47 backend and frontend summary tests execute and the UI builds.
- FDP-46/FDP-47 skip guards still run before tests.

Does not prove:
- Workspace runtime provider ownership or API client boundary hardening introduced later.

Required for merge:
- Yes

Primary proof files:
- `analyst-console-ui/src/App.test.jsx`
- `analyst-console-ui/src/pages/AlertsListPage.test.jsx`
- `analyst-console-ui/src/components/FraudCaseWorkQueuePanel.test.jsx`
- `alert-service/src/test/java/**/ScoredTransaction*Test.java`
- `alert-service/src/test/java/**/Fdp47*Test.java`
- `scripts/ci/verify-junit-reports.mjs`

Related FDP branches:
- FDP-46
- FDP-47

Candidate for consolidation:
- No
- Rationale: It is already the consolidated analyst console product gate and should stay separate from FDP-50 through FDP-53 architecture proof.

## FDP-48 BFF Session & Request Lifecycle

Purpose:
- Prove BFF session/request lifecycle behavior and frontend lifecycle tests.

Proves:
- BFF backend security tests, frontend lifecycle tests, API boundary guard, and report verification execute.

Does not prove:
- FDP-50 runtime API client replacement or later workspace decomposition.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Bff*Test.java`
- `analyst-console-ui/src/auth/authProvider.test.js`
- `scripts/check-fdp48-api-client-boundary.mjs`

Related FDP branches:
- FDP-48

Candidate for consolidation:
- No
- Rationale: It spans backend BFF security and frontend lifecycle, with different ownership than the architecture gate.

## FDP-49 Security Route Boundary Hardening

Purpose:
- Prove security route ownership, matcher order, API client boundary, and route fallback guardrails.

Proves:
- FDP-49 backend route tests, frontend API boundary tests, grep guardrails, and report verification execute.

Does not prove:
- FDP-50 through FDP-53 workspace runtime behavior or production IAM hardening.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/com/frauddetection/alert/security/config/*`
- `analyst-console-ui/src/api/apiClientBoundary.test.js`
- `scripts/check-fdp49-api-client-boundary.mjs`

Related FDP branches:
- FDP-49

Candidate for consolidation:
- No
- Rationale: Security route boundary failures should not be hidden inside a generic frontend job.

## FDP-54 CI Docs Governance

Purpose:
- Prove FDP-54 documentation and CI evidence tooling stays coherent.

Proves:
- Shared scope helper smoke checks pass.
- Documentation overclaim guard passes.
- Current workflow jobs are represented in `docs/ci_evidence_map.md`.

Does not prove:
- Runtime frontend behavior, backend authorization, or Docker build behavior.

Required for merge:
- Yes

Primary proof files:
- `scripts/check-fdp-scope-helpers-smoke.mjs`
- `scripts/check-doc-overclaims.mjs`
- `scripts/compare-ci-jobs.mjs`
- `docs/ci_evidence_map.md`

Related FDP branches:
- FDP-54

Candidate for consolidation:
- No
- Rationale: It is lightweight docs/tooling governance with different failure output.

## Analyst Console Frontend Architecture Gate

Purpose:
- Replace the individual FDP-50, FDP-51, FDP-52, and FDP-53 frontend architecture jobs with one merge-blocking job that keeps separate proof sections.

Proves:
- FDP-50 API client boundary tests and guards run.
- FDP-51 runtime provider tests and scope guard run.
- FDP-52 detail UX tests and scope guard run.
- FDP-53 runtime ownership tests and scope guard run.
- Full frontend build runs after the architecture test sections.

Does not prove:
- Backend authorization enforcement, backend mutation semantics, Docker compose stack behavior, or enterprise IAM.

Required for merge:
- Yes

Primary proof files:
- `analyst-console-ui/src/api/apiClientBoundary.test.js`
- `analyst-console-ui/src/workspace/WorkspaceRuntimeProvider.test.jsx`
- `analyst-console-ui/src/workspace/WorkspaceDetailRouter.test.jsx`
- `analyst-console-ui/src/workspace/WorkspaceRuntimes.test.jsx`
- `scripts/check-fdp50-api-client-boundary.mjs`
- `scripts/check-fdp50-scope.mjs`
- `scripts/check-fdp51-scope.mjs`
- `scripts/check-fdp52-scope.mjs`
- `scripts/check-fdp53-scope.mjs`

Related FDP branches:
- FDP-50
- FDP-51
- FDP-52
- FDP-53
- FDP-54

Candidate for consolidation:
- No
- Rationale: This is the consolidated job. Further consolidation would reduce failure diagnosability.

## FDP-50 Frontend API Client Runtime Boundary

Replaced by:
- Analyst Console Frontend Architecture Gate

Purpose:
- Former standalone FDP-50 frontend API client runtime boundary job.

Proves:
- Now proved by the `FDP-50 API client boundary` section of `Analyst Console Frontend Architecture Gate`.

Does not prove:
- Backend authorization or later workspace ownership behavior by itself.

Required for merge:
- Yes, through replacement job

Primary proof files:
- `analyst-console-ui/src/api/apiClientBoundary.test.js`
- `scripts/check-fdp50-api-client-boundary.mjs`
- `scripts/check-fdp50-scope.mjs`

Related FDP branches:
- FDP-50
- FDP-54

Candidate for consolidation:
- Yes
- Rationale: Implemented by FDP-54 with the same tests, guards, and report verification inside one architecture gate.

## FDP-51 Analyst Console Workspace Runtime

Replaced by:
- Analyst Console Frontend Architecture Gate

Purpose:
- Former standalone FDP-51 workspace runtime provider job.

Proves:
- Now proved by the `FDP-51 runtime provider` section of `Analyst Console Frontend Architecture Gate`.

Does not prove:
- Backend authorization or FDP-52/FDP-53 behavior by itself.

Required for merge:
- Yes, through replacement job

Primary proof files:
- `analyst-console-ui/src/workspace/WorkspaceRuntimeProvider.test.jsx`
- `scripts/check-fdp51-scope.mjs`

Related FDP branches:
- FDP-51
- FDP-54

Candidate for consolidation:
- Yes
- Rationale: Implemented by FDP-54 with preserved tests, scope guard, and report verification.

## FDP-52 Workspace Split Detail UX

Replaced by:
- Analyst Console Frontend Architecture Gate

Purpose:
- Former standalone FDP-52 workspace split and detail UX job.

Proves:
- Now proved by the `FDP-52 detail UX` section of `Analyst Console Frontend Architecture Gate`.

Does not prove:
- Backend mutation rollback behavior or authorization enforcement.

Required for merge:
- Yes, through replacement job

Primary proof files:
- `analyst-console-ui/src/workspace/WorkspaceDetailRouter.test.jsx`
- `analyst-console-ui/src/components/DetailHeader.test.jsx`
- `analyst-console-ui/src/utils/idempotencyKey.test.js`
- `scripts/check-fdp52-scope.mjs`

Related FDP branches:
- FDP-52
- FDP-54

Candidate for consolidation:
- Yes
- Rationale: Implemented by FDP-54 with preserved test report checks and scope guard.

## FDP-53 Workspace Runtime Ownership

Replaced by:
- Analyst Console Frontend Architecture Gate

Purpose:
- Former standalone FDP-53 workspace runtime ownership job.

Proves:
- Now proved by the `FDP-53 runtime ownership` section of `Analyst Console Frontend Architecture Gate`.

Does not prove:
- Backend endpoint behavior, new auth modes, or product workflow changes.

Required for merge:
- Yes, through replacement job

Primary proof files:
- `analyst-console-ui/src/workspace/WorkspaceRouteRegistry.test.jsx`
- `analyst-console-ui/src/workspace/WorkspaceRuntimes.test.jsx`
- `scripts/check-fdp53-scope.mjs`

Related FDP branches:
- FDP-53
- FDP-54

Candidate for consolidation:
- Yes
- Rationale: Implemented by FDP-54 with preserved runtime ownership tests, guards, and report verification.

## FDP-36 Real Alert-Service Kill Proof

Purpose:
- Prove real alert-service process kill/restart chaos scenarios.

Proves:
- FDP-36 chaos tests execute and proof summary artifacts are generated.

Does not prove:
- Production image behavior, frontend architecture, or release governance.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/RegulatedMutationRealAlertService*IT.java`
- `docs/testing/fdp_36_real_chaos_proof.md`

Related FDP branches:
- FDP-36

Candidate for consolidation:
- No
- Rationale: Chaos proof needs clear standalone output.

## FDP-37 Production Image Chaos Proof

Purpose:
- Prove FDP-37 production-image chaos evidence pack generation and checks.

Proves:
- Production-like alert-service image builds, chaos suite executes, proof artifacts are verified, and reports are packaged as a single archive.

Does not prove:
- FDP-50 through FDP-53 frontend architecture, release promotion, or enterprise IAM.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp37*Test.java`
- `docs/testing/fdp_37_production_image_chaos_proof.md`
- `.github/workflows/ci.yml`

Related FDP branches:
- FDP-37

Candidate for consolidation:
- No
- Rationale: Image chaos artifacts and upload behavior are specialized.

## Regulated Mutation Regression Gate

Purpose:
- Prove core regulated mutation regression scenarios.

Proves:
- Required regulated mutation tests execute under Maven.

Does not prove:
- Frontend runtime behavior, branch docs, or external platform controls.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/RegulatedMutation*Test.java`
- `docs/testing/fdp_35_regulated_mutation_readiness_proof.md`

Related FDP branches:
- FDP-29
- FDP-35

Candidate for consolidation:
- No
- Rationale: This is a core mutation safety gate and must remain visible.

## FDP-38 Live Runtime Checkpoint Chaos Proof

Purpose:
- Prove FDP-38 live runtime checkpoint fixture chaos behavior.

Proves:
- FDP-38 chaos tests, evidence files, and artifact checks execute.

Does not prove:
- Production image live proof or frontend architecture behavior.

Required for merge:
- Yes

Primary proof files:
- `alert-service/src/test/java/**/Fdp38*Test.java`
- `docs/testing/fdp_38_live_runtime_checkpoint_proof.md`

Related FDP branches:
- FDP-38

Candidate for consolidation:
- No
- Rationale: Chaos evidence and limitation checks have separate review value.

## FDP-39 Release Governance Gate

Purpose:
- Prove release governance readiness artifacts and limitation checks.

Proves:
- FDP-39 governance artifacts, tests, and forbidden-claim checks execute.

Does not prove:
- Platform branch protection enforcement or frontend runtime behavior.

Required for merge:
- Yes

Primary proof files:
- `docs/release/fdp_39_*`
- `alert-service/src/test/java/**/Fdp39*Test.java`
- `.github/workflows/ci.yml`

Related FDP branches:
- FDP-39

Candidate for consolidation:
- No
- Rationale: Release governance has compliance meaning and should stay separate.

## FDP-40 Release Controls

Purpose:
- Prove FDP-40 release control artifacts and readiness-only checks.

Proves:
- Manifest, attestation, evidence verification, cosign readiness mode, and FDP-40 tests execute.

Does not prove:
- External platform enforcement, actual signing enforcement, registry immutability enforcement, or frontend architecture.

Required for merge:
- Yes

Primary proof files:
- `docs/release/fdp_40_*`
- `scripts/fdp40-*.sh`
- `alert-service/src/test/java/**/Fdp40*Test.java`

Related FDP branches:
- FDP-40

Candidate for consolidation:
- No
- Rationale: Release controls have separate ownership and should not be combined with docs simplification.

## Docker Compose Build

Purpose:
- Prove the strongest compose stack config validates and images build.

Proves:
- Compose config with OIDC, service identity mTLS, and trust-authority JWT overlays is syntactically valid and images build.

Does not prove:
- Runtime health, enterprise IAM, or every service-to-service request path.

Required for merge:
- Yes

Primary proof files:
- `deployment/docker-compose.yml`
- `deployment/docker-compose.oidc.yml`
- `deployment/docker-compose.service-identity-mtls.yml`
- `deployment/docker-compose.trust-authority-jwt.yml`

Related FDP branches:
- FDP-28
- FDP-37
- FDP-39
- FDP-40
- FDP-50
- FDP-51
- FDP-52
- FDP-53

Candidate for consolidation:
- No
- Rationale: Docker build proof is downstream of backend, frontend, chaos, release, and architecture gates.
