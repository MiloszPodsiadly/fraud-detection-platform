# Reviewer Checklist

## 1. Scope

- [ ] PR title and body match actual changed files.
- [ ] Non-goals are explicit.
- [ ] No hidden backend/product changes.
- [ ] No new workflow introduced under "refactor".

NO-GO if:
- PR claims frontend-only but changes backend production code.
- PR adds mutation, export, bulk, or assignment without explicit scope.

## 2. Backend / ACID

- [ ] No transaction boundary changes unless explicitly scoped.
- [ ] No idempotency behavior changes unless explicitly scoped.
- [ ] No outbox/Kafka/finality changes unless explicitly scoped.
- [ ] Existing regulated mutation gates still pass.

NO-GO if:
- Mutation semantics change without regression proof.

## 3. Frontend Runtime

- [ ] No raw fetch outside API/auth boundary.
- [ ] No default API wrapper use in auth-sensitive UI.
- [ ] Session switch and logout do not preserve stale state.
- [ ] Aborted/stale requests do not commit UI state.

## 4. Auth / Security Boundary

- [ ] Backend remains enforcement source.
- [ ] Frontend capabilities are UX/runtime hints only.
- [ ] No broad `permitAll` or route fallback security overclaim.
- [ ] CSRF/session/BFF claims are accurate.

## 5. CI Evidence

- [ ] Required gates are green.
- [ ] Consolidated jobs still run replacement tests.
- [ ] No skipped/focused tests.
- [ ] Evidence map updated if CI changed.

## 6. Documentation / Overclaim

- [ ] Docs describe actual behavior, not aspirational behavior.
- [ ] No production posture claim without checklist/evidence.
- [ ] No fake KMS/WORM/notarization/finality claims.
- [ ] "Does not prove" is documented for major evidence gates.

## 7. Final Review

- [ ] All blockers resolved.
- [ ] Remaining risks are documented as non-blocking.
- [ ] Full CI green.
- [ ] Rollback/impact is understood.
