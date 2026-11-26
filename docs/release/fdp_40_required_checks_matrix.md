# FDP-40 Required Checks Matrix

The authoritative machine-readable mapping is `fdp_40_required_checks_matrix.json`.

All entries are required, blocking, and use `NO_GO` failure policy.

The FDP-157 matrix has one external required check: `platform-security-gate`, with the same-run `platform-security-evidence-<GITHUB_SHA>` artifact. CI producer jobs remain blocking through that gate's explicit dependencies and result validation.

Required checks must be enforced by branch protection or platform policy outside the repository before production enablement.

FDP-40 defines the required checks matrix, but it does not verify GitHub branch protection through GitHub APIs.
External branch protection control is required before production enablement.
