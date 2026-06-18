# Promotion Review Readiness UI Panel

Status: current FDP-114 frontend implementation.

FDP-114 is a read-only UI panel in the existing Shadow Performance diagnostics workspace. It consumes the FDP-112
authorized read API:

`GET /api/v1/governance/promotion-review-readiness/current`

The panel displays the current FDP-111 `PromotionReviewReadinessReport` as a bounded diagnostic human-review
readiness artifact. Backend authorization remains authoritative through `promotion-readiness:read`; frontend
capability mapping is a UX/request gate only.

## Boundaries

FDP-114:

- consumes FDP-112;
- does not generate reports;
- does not approve promotion;
- does not recommend thresholds;
- does not trigger workflow;
- does not change scoring;
- does not mutate model registry;
- does not authorize payments;
- does not recommend analyst actions.

The UI does not call raw FDP-102/FDP-103/FDP-104 artifact endpoints, display raw artifact content, or expose
transaction references, customer identifiers, account/card/device/merchant identifiers, filesystem paths, stack
traces, internal class names, or secrets.

## Runtime Behavior

The panel is integrated into the existing Shadow Performance diagnostics workspace instead of creating a workflow
route. Shadow Performance Summary state and Promotion Review Readiness state are loaded independently:

- Shadow Performance failure does not fabricate Promotion Review Readiness state.
- Promotion Review Readiness failure does not fabricate Shadow Performance state.
- `REVIEWABLE` is displayed only as "Human review may begin" and not as model promotion approval.
- Invalid or malformed responses render a safe invalid diagnostic response state.

## Non-Goals

FDP-114 does not add approval buttons, rejection buttons, promotion or deployment actions, workflow actions, threshold
changes, payment actions, analyst action recommendations, report generation, backend code, or OpenAPI changes.
