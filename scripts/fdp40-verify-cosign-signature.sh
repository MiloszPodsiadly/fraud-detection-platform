#!/usr/bin/env bash
set -euo pipefail

output_dir="target/fdp40-release"
mkdir -p "$output_dir"

if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
  PYTHON_CMD="python"
elif command -v py >/dev/null 2>&1 && py -3 --version >/dev/null 2>&1; then
  PYTHON_CMD="py -3"
else
  echo "Python 3 is required to write FDP-40 cosign readiness output" >&2
  exit 1
fi

if [[ "${FDP40_COSIGN_ENFORCEMENT:-false}" != "true" ]]; then
  $PYTHON_CMD - "$output_dir" <<'PY'
import json
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
data = {
    "verification_performed": False,
    "readiness_only": True,
    "external_platform_control_required": True,
    "production_enabled": False,
    "failure_reasons": [],
}
(output / "fdp40-cosign-verification.json").write_text(
    json.dumps(data, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
(output / "fdp40-cosign-verification.md").write_text(
    "# FDP-40 Cosign Verification Readiness\n\n"
    "- verification_performed: `false`\n"
    "- readiness_only: `true`\n"
    "- external_platform_control_required: `true`\n",
    encoding="utf-8",
)
PY
  echo "FDP-40 cosign readiness mode passed"
  exit 0
fi

failures=()
if ! command -v cosign >/dev/null 2>&1; then
  failures+=("cosign_missing")
fi
if [[ -z "${FDP40_RELEASE_IMAGE_DIGEST:-}" ]]; then
  failures+=("release_image_digest_missing")
fi
if [[ -z "${FDP40_COSIGN_CERT_IDENTITY:-}" ]]; then
  failures+=("cert_identity_missing")
fi
if [[ -z "${FDP40_COSIGN_CERT_ISSUER:-}" ]]; then
  failures+=("cert_issuer_missing")
fi
if [[ ${#failures[@]} -gt 0 ]]; then
  printf 'FDP-40 cosign enforcement missing requirements: %s\n' "${failures[*]}" >&2
  exit 1
fi

cosign verify \
  --certificate-identity "$FDP40_COSIGN_CERT_IDENTITY" \
  --certificate-oidc-issuer "$FDP40_COSIGN_CERT_ISSUER" \
  "$FDP40_RELEASE_IMAGE_DIGEST"

$PYTHON_CMD - "$output_dir" <<'PY'
import json
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
data = {
    "verification_performed": True,
    "readiness_only": False,
    "external_platform_control_required": False,
    "production_enabled": False,
    "failure_reasons": [],
}
(output / "fdp40-cosign-verification.json").write_text(
    json.dumps(data, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
