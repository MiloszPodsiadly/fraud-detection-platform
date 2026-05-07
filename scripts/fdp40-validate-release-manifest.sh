#!/usr/bin/env bash
set -euo pipefail

manifest="${1:-docs/release/FDP-40-release-manifest-template.yaml}"

if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
  PYTHON_CMD="python"
elif command -v py >/dev/null 2>&1 && py -3 --version >/dev/null 2>&1; then
  PYTHON_CMD="py -3"
else
  echo "Python 3 is required to validate FDP-40 release manifest" >&2
  exit 1
fi

$PYTHON_CMD - "$manifest" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = {}
for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
    if raw_line.startswith("  ") and ":" in raw_line:
        print(f"nested YAML is not supported at line {line_number}", file=sys.stderr)
        sys.exit(1)
    if raw_line.lstrip().startswith("- "):
        print(f"YAML lists are not supported at line {line_number}", file=sys.stderr)
        sys.exit(1)
    line = raw_line.strip()
    if not line or line.startswith("#") or ":" not in line:
        continue
    key, value = line.split(":", 1)
    data[key.strip()] = value.strip().strip('"')

required = [
    "release_manifest_version", "commit_sha", "branch_name", "github_run_id", "workflow_name",
    "release_image_name", "release_image_tag", "release_image_digest", "release_image_id",
    "dockerfile_path", "builder_identity", "build_workflow", "build_timestamp",
    "fdp39_provenance_artifact_ref", "fdp39_release_image_digest", "fixture_image_digest",
    "fixture_image_promotable", "ready_for_enablement_review", "production_enabled",
    "readiness_only", "external_platform_controls_required", "signing_verification_performed",
    "registry_immutability_enforced_by_fdp40", "environment_protection_verified_by_fdp40",
    "branch_protection_verified_by_fdp40",
    "release_config_pr_required", "dual_control_required", "rollback_plan_ref",
    "operator_drill_ref", "security_review_ref",
]
missing = [key for key in required if not data.get(key)]
failures = []
if missing:
    failures.append(f"missing={missing}")
if not data.get("release_image_digest", "").startswith("sha256:"):
    failures.append("release_image_digest must start with sha256:")
if not data.get("release_image_id", "").startswith("sha256:"):
    failures.append("release_image_id must start with sha256:")
if data.get("release_image_digest") != data.get("fdp39_release_image_digest"):
    failures.append("release digest must match FDP-39 digest")
if data.get("release_image_digest") == data.get("fixture_image_digest"):
    failures.append("fixture image digest cannot be release digest")
if data.get("fixture_image_promotable") != "false":
    failures.append("fixture_image_promotable must be false")
if data.get("production_enabled") != "false":
    failures.append("production_enabled must be false")
if data.get("readiness_only") != "true":
    failures.append("readiness_only must be true")
if data.get("external_platform_controls_required") != "true":
    failures.append("external_platform_controls_required must be true")
for field in [
    "signing_verification_performed",
    "registry_immutability_enforced_by_fdp40",
    "environment_protection_verified_by_fdp40",
    "branch_protection_verified_by_fdp40",
]:
    if data.get(field) != "false":
        failures.append(f"{field} must be false")
if data.get("release_config_pr_required") != "true":
    failures.append("release_config_pr_required must be true")
if data.get("dual_control_required") != "true":
    failures.append("dual_control_required must be true")
if data.get("dockerfile_path") != "deployment/Dockerfile.backend":
    failures.append("dockerfile_path must be deployment/Dockerfile.backend")
joined = "\n".join(data.values())
for token in ["LOCAL_", "PLACEHOLDER", "TO_BE_FILLED", "UNKNOWN", "NOT_PROVIDED"]:
    if token in joined:
        failures.append(f"forbidden token: {token}")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print("FDP-40 release manifest validation passed")
PY
