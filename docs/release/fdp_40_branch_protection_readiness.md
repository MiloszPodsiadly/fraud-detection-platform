# FDP-40 Branch Protection Readiness

Branch protection is required before production enablement. FDP-40 does not verify GitHub branch protection through GitHub APIs.

Required platform settings:

- required status checks before merge
- status checks matching `docs/release/fdp_40_required_checks_matrix.json`
- stale review dismissal
- code owner reviews
- no admin bypass
- recommended linear history
- recommended up-to-date branch requirement

Because this is an external GitHub platform control, FDP-40 can only document and test readiness. Production enablement is NO-GO until the protection is configured on `master`.
