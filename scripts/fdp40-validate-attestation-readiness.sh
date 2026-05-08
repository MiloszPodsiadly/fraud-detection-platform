#!/usr/bin/env bash
set -euo pipefail

attestation="${1:-docs/release/fdp-40-attestation-readiness-template.json}"
manifest="${2:-docs/release/fdp-40-release-manifest-template.yaml}"

if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
  PYTHON_CMD="python"
elif command -v py >/dev/null 2>&1 && py -3 --version >/dev/null 2>&1; then
  PYTHON_CMD="py -3"
else
  echo "Python 3 is required to validate FDP-40 attestation readiness" >&2
  exit 1
fi

$PYTHON_CMD - "$attestation" "$manifest" <<'PY'
import json
import pathlib
import sys

attestation = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
manifest = {}
for line in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if line and not line.startswith("#") and ":" in line:
        key, value = line.split(":", 1)
        manifest[key.strip()] = value.strip().strip('"')

required = [
    "image_digest", "signature_subject", "certificate_identity", "certificate_issuer",
    "builder_identity", "source_repository", "commit_sha", "workflow_name", "workflow_run_id",
    "dockerfile_path", "build_type", "build_trigger", "provenance_predicate_type",
    "slsa_version_or_equivalent", "artifact_lineage_ref", "fdp39_provenance_ref",
]
failures = [f"missing {key}" for key in required if not str(attestation.get(key, "")).strip()]
if attestation.get("image_digest") != manifest.get("release_image_digest"):
    failures.append("attestation digest must match release manifest digest")
if attestation.get("image_digest") == manifest.get("fixture_image_digest"):
    failures.append("fixture attestation cannot satisfy release attestation")
if not str(attestation.get("image_digest", "")).startswith("sha256:"):
    failures.append("image digest must be sha256")
if ":" in str(attestation.get("image_digest", "")) and not str(attestation.get("signature_subject", "")).endswith(attestation["image_digest"]):
    failures.append("signature subject must bind the image digest")
if attestation.get("dockerfile_path") != "deployment/Dockerfile.backend":
    failures.append("dockerfile_path must be release Dockerfile")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print("FDP-40 attestation readiness validation passed")
PY
