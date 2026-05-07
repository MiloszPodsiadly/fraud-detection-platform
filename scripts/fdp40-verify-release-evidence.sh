#!/usr/bin/env bash
set -euo pipefail

manifest="${1:-docs/release/FDP-40-release-manifest-template.yaml}"
attestation="${2:-docs/release/FDP-40-attestation-readiness-template.json}"
fdp39="${3:-docs/release/FDP-40-fdp39-provenance-reference.json}"
checks="${4:-docs/release/FDP-40-required-checks-matrix.json}"
output_dir="target/fdp40-release"
mkdir -p "$output_dir"

if command -v python3 >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
  PYTHON_CMD="python3"
elif command -v python >/dev/null 2>&1 && python --version >/dev/null 2>&1; then
  PYTHON_CMD="python"
elif command -v py >/dev/null 2>&1 && py -3 --version >/dev/null 2>&1; then
  PYTHON_CMD="py -3"
else
  echo "Python 3 is required to verify FDP-40 release evidence" >&2
  exit 1
fi

$PYTHON_CMD - "$manifest" "$attestation" "$fdp39" "$checks" "$output_dir" <<'PY'
import json
import pathlib
import sys

manifest = {}
for line_number, raw_line in enumerate(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines(), start=1):
    if raw_line.startswith("  ") and ":" in raw_line:
        print(f"nested YAML is not supported at line {line_number}", file=sys.stderr)
        sys.exit(1)
    if raw_line.lstrip().startswith("- "):
        print(f"YAML lists are not supported at line {line_number}", file=sys.stderr)
        sys.exit(1)
    line = raw_line.strip()
    if line and not line.startswith("#") and ":" in line:
        key, value = line.split(":", 1)
        manifest[key.strip()] = value.strip().strip('"')
attestation = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
fdp39 = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
checks = json.loads(pathlib.Path(sys.argv[4]).read_text(encoding="utf-8"))["checks"]
output = pathlib.Path(sys.argv[5])

failure_reasons = []
manifest_valid = manifest.get("release_image_digest", "").startswith("sha256:") and manifest.get("release_image_id", "").startswith("sha256:")
attestation_valid = bool(attestation.get("signature_subject")) and bool(attestation.get("builder_identity")) and bool(attestation.get("source_repository"))
fdp39_digest_match = manifest.get("release_image_digest") == fdp39.get("release_image_digest_or_id")
attestation_digest_match = attestation.get("image_digest") == manifest.get("release_image_digest")
fixture_not_promoted = manifest.get("release_image_digest") != manifest.get("fixture_image_digest") and fdp39.get("fixture_image_release_candidate_allowed") is False
required_checks_present = all(check.get("required") is True and check.get("blocking") is True for check in checks)
production_enabled_false = manifest.get("production_enabled") == "false"
readiness_only = manifest.get("readiness_only") == "true"
external_platform_controls_required = manifest.get("external_platform_controls_required") == "true"
signing_verification_performed = manifest.get("signing_verification_performed") == "true"
registry_immutability_enforced_by_fdp40 = manifest.get("registry_immutability_enforced_by_fdp40") == "true"
environment_protection_verified_by_fdp40 = manifest.get("environment_protection_verified_by_fdp40") == "true"
branch_protection_verified_by_fdp40 = manifest.get("branch_protection_verified_by_fdp40") == "true"
release_config_pr_required = manifest.get("release_config_pr_required") == "true"
dual_control_required = manifest.get("dual_control_required") == "true"
no_mutable_tag_only = bool(manifest.get("release_image_digest")) and bool(manifest.get("release_image_tag"))
if not manifest_valid:
    failure_reasons.append("manifest_invalid")
if not attestation_valid:
    failure_reasons.append("attestation_invalid")
if not fdp39_digest_match:
    failure_reasons.append("fdp39_digest_mismatch")
if not attestation_digest_match:
    failure_reasons.append("attestation_digest_mismatch")
if not fixture_not_promoted:
    failure_reasons.append("fixture_promoted")
if not required_checks_present:
    failure_reasons.append("required_checks_missing")
if not production_enabled_false:
    failure_reasons.append("production_enabled_true")
if not readiness_only:
    failure_reasons.append("readiness_only_missing")
if not external_platform_controls_required:
    failure_reasons.append("external_platform_controls_missing")
if signing_verification_performed:
    failure_reasons.append("signing_verification_unexpected")
if registry_immutability_enforced_by_fdp40:
    failure_reasons.append("registry_enforcement_unexpected")
if environment_protection_verified_by_fdp40:
    failure_reasons.append("environment_verification_unexpected")
if branch_protection_verified_by_fdp40:
    failure_reasons.append("branch_protection_verification_unexpected")
if not release_config_pr_required:
    failure_reasons.append("release_config_pr_not_required")
if not dual_control_required:
    failure_reasons.append("dual_control_not_required")
if not no_mutable_tag_only:
    failure_reasons.append("mutable_tag_only")
passed = not failure_reasons
data = {
    "verification_passed": passed,
    "manifest_valid": manifest_valid,
    "attestation_valid": attestation_valid,
    "fdp39_digest_match": fdp39_digest_match,
    "attestation_digest_match": attestation_digest_match,
    "fixture_not_promoted": fixture_not_promoted,
    "required_checks_present": required_checks_present,
    "production_enabled_false": production_enabled_false,
    "readiness_only": readiness_only,
    "external_platform_controls_required": external_platform_controls_required,
    "signing_verification_performed": signing_verification_performed,
    "registry_immutability_enforced_by_fdp40": registry_immutability_enforced_by_fdp40,
    "environment_protection_verified_by_fdp40": environment_protection_verified_by_fdp40,
    "branch_protection_verified_by_fdp40": branch_protection_verified_by_fdp40,
    "release_config_pr_required": release_config_pr_required,
    "dual_control_required": dual_control_required,
    "no_mutable_tag_only": no_mutable_tag_only,
    "failure_reasons": failure_reasons,
}
(output / "fdp40-release-evidence-verification.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
(output / "fdp40-release-evidence-verification.md").write_text(
    "# FDP-40 Release Evidence Verification\n\n"
    + "\n".join(f"- {key}: `{value}`" for key, value in data.items())
    + "\n",
    encoding="utf-8",
)
if not passed:
    print(json.dumps(data, indent=2), file=sys.stderr)
    sys.exit(1)
print("FDP-40 release evidence verification passed")
PY
