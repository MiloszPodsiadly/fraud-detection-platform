# FDP-40 Cosign Verification Readiness

FDP-40 default mode is readiness-only. It does not perform real cosign, Sigstore, or Rekor verification unless `FDP40_COSIGN_ENFORCEMENT=true` is explicitly set in a future enforcement-grade job.

Readiness mode emits:

- `verification_performed: false`
- `readiness_only: true`
- `external_platform_control_required: true`
- `production_enabled: false`

Enforcement mode must verify the release image by digest, not by mutable tag. It must provide expected certificate identity and issuer. If any of those inputs are absent, the script fails closed.

No FDP-40 artifact may claim `signing_verified: true` unless the enforcement mode actually ran successfully.
