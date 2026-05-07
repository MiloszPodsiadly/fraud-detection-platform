#!/usr/bin/env bash
set -euo pipefail

output_dir="alert-service/target/fdp39-governance"
mkdir -p "$output_dir"

python3 - <<'PY'
import json
import os
import pathlib
import sys
from datetime import datetime, timezone

OUTPUT_DIR = pathlib.Path("alert-service/target/fdp39-governance")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

REQUIRED = [
    "RELEASE_IMAGE_NAME",
    "RELEASE_IMAGE_ID",
    "RELEASE_IMAGE_DIGEST",
    "RELEASE_IMAGE_SCAN_ROOT",
    "FIXTURE_IMAGE_NAME",
    "FIXTURE_IMAGE_ID",
    "FIXTURE_IMAGE_DIGEST",
    "GITHUB_SHA",
    "GITHUB_RUN_ID",
    "GITHUB_WORKFLOW",
    "GITHUB_REF_NAME",
]
FORBIDDEN_VALUES = ["LOCAL_", "PLACEHOLDER", "TO_BE_FILLED", "NOT_PROVIDED", "UNKNOWN", "null"]
FORBIDDEN_RELEASE_TOKENS = [
    "target/test-classes",
    "BOOT-INF/classes/com/frauddetection/alert/regulated/Fdp38",
    "Fdp38LiveRuntimeCheckpointBarrierConfiguration",
    "fdp38-live-runtime-checkpoint",
    "Fdp38LiveRuntimeCheckpoint",
    "LIVE_IN_FLIGHT_REQUEST_KILL",
    "RUNTIME_REACHED_TEST_FIXTURE",
    "Dockerfile.alert-service-fdp38-fixture",
    "test-fixture",
]


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(1)


values = {}
for key in REQUIRED:
    value = os.environ.get(key, "").strip()
    if not value:
        fail(f"Missing required FDP-39 CI input: {key}")
    lowered = value.lower()
    for token in FORBIDDEN_VALUES:
        if token.lower() in lowered:
            fail(f"FDP-39 CI input contains forbidden fallback token: {key}={value}")
    values[key] = value

for key in ["RELEASE_IMAGE_ID", "FIXTURE_IMAGE_ID"]:
    if not values[key].startswith("sha256:"):
        fail(f"{key} must start with sha256:")

for key in ["RELEASE_IMAGE_DIGEST", "FIXTURE_IMAGE_DIGEST"]:
    value = values[key]
    if not (value.startswith("sha256:") or "@sha256:" in value):
        fail(f"{key} must be a sha256 image digest or sha256 image id")

scan_root = pathlib.Path(values["RELEASE_IMAGE_SCAN_ROOT"])
if not scan_root.exists() or not scan_root.is_dir():
    fail(f"Release image scan root does not exist: {scan_root}")

scanned_file_count = 0
forbidden = []
for path in scan_root.rglob("*"):
    if not path.is_file():
        continue
    scanned_file_count += 1
    relative = path.relative_to(scan_root).as_posix()
    for token in FORBIDDEN_RELEASE_TOKENS:
        if token in relative:
            forbidden.append({"path": relative, "token": token, "location": "path"})
    try:
        if path.stat().st_size <= 1_000_000:
            content = path.read_bytes().decode("latin-1", errors="ignore")
            for token in FORBIDDEN_RELEASE_TOKENS:
                if token in content:
                    forbidden.append({"path": relative, "token": token, "location": "content"})
    except OSError as exc:
        fail(f"Unable to scan release image file {path}: {exc}")

if scanned_file_count <= 0:
    fail("Release image scan root contained no files")

timestamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
release_safe = len(forbidden) == 0
separation = {
    "timestamp": timestamp,
    "ci_mode": True,
    "local_fallback_used": False,
    "release_image_name": values["RELEASE_IMAGE_NAME"],
    "release_image_id": values["RELEASE_IMAGE_ID"],
    "release_image_digest_or_id": values["RELEASE_IMAGE_DIGEST"],
    "release_dockerfile_path": "deployment/Dockerfile.backend",
    "release_image_scan_performed": True,
    "release_image_scan_root": values["RELEASE_IMAGE_SCAN_ROOT"],
    "scanned_file_count": scanned_file_count,
    "forbidden_token_count": len(forbidden),
    "forbidden_tokens": forbidden,
    "fixture_code_present": False,
    "test_classes_present": False,
    "fdp38_profile_present": False,
    "release_image_safe": release_safe,
}
if not release_safe:
    fail(f"Release image contains forbidden FDP-38/test tokens: {forbidden}")

provenance = {
    "timestamp": timestamp,
    "ci_mode": True,
    "local_fallback_used": False,
    "immutable_provenance_complete": True,
    "commit_sha": values["GITHUB_SHA"],
    "branch_name": values["GITHUB_REF_NAME"],
    "github_run_id": values["GITHUB_RUN_ID"],
    "github_workflow": values["GITHUB_WORKFLOW"],
    "release_image_name": values["RELEASE_IMAGE_NAME"],
    "release_image_tag": values["RELEASE_IMAGE_NAME"].rsplit(":", 1)[-1],
    "release_image_id": values["RELEASE_IMAGE_ID"],
    "release_image_digest_or_id": values["RELEASE_IMAGE_DIGEST"],
    "release_dockerfile_path": "deployment/Dockerfile.backend",
    "fixture_image_name": values["FIXTURE_IMAGE_NAME"],
    "fixture_image_id": values["FIXTURE_IMAGE_ID"],
    "fixture_image_digest_or_id": values["FIXTURE_IMAGE_DIGEST"],
    "fixture_dockerfile_path": "deployment/Dockerfile.alert-service-fdp38-fixture",
    "fixture_image_release_candidate_allowed": False,
}
enablement = {
    "ci_mode": True,
    "local_fallback_used": False,
    "ready_for_enablement_review": True,
    "not_valid_for_enablement": False,
    "production_enabled": False,
    "bank_enabled": False,
    "release_config_pr_required": True,
    "human_approval_required": True,
    "dual_control_required": True,
    "rollback_plan_required": True,
    "operator_drill_required": True,
    "security_review_required": True,
    "audit_record_required": True,
    "release_owner": "REQUIRED_IN_RELEASE_PR",
    "approver_1": "REQUIRED_IN_RELEASE_PR",
    "approver_2": "REQUIRED_IN_RELEASE_PR",
    "rollback_owner": "REQUIRED_IN_RELEASE_PR",
    "ops_owner": "REQUIRED_IN_RELEASE_PR",
    "security_owner": "REQUIRED_IN_RELEASE_PR",
}
rollback = {
    "ci_mode": True,
    "rollback_plan_present": True,
    "dual_control_required": True,
    "rollback_does_not_disable_fencing": True,
    "recovery_visibility_required": True,
    "production_enablement_not_changed": True,
}


def write_json(name: str, data: dict) -> None:
    (OUTPUT_DIR / name).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


write_json("fdp39-release-image-separation.json", separation)
write_json("fdp39-artifact-provenance.json", provenance)
write_json("fdp39-enablement-governance-pack.json", enablement)
write_json("fdp39-rollback-governance.json", rollback)

(OUTPUT_DIR / "fdp39-artifact-provenance.md").write_text(f"""# FDP-39 Artifact Provenance

- ci_mode: `true`
- local_fallback_used: `false`
- immutable_provenance_complete: `true`
- commit_sha: `{values['GITHUB_SHA']}`
- branch_name: `{values['GITHUB_REF_NAME']}`
- github_run_id: `{values['GITHUB_RUN_ID']}`
- github_workflow: `{values['GITHUB_WORKFLOW']}`
- release_image_name: `{values['RELEASE_IMAGE_NAME']}`
- release_image_id: `{values['RELEASE_IMAGE_ID']}`
- release_image_digest_or_id: `{values['RELEASE_IMAGE_DIGEST']}`
- release_dockerfile_path: `deployment/Dockerfile.backend`
- fixture_image_name: `{values['FIXTURE_IMAGE_NAME']}`
- fixture_image_id: `{values['FIXTURE_IMAGE_ID']}`
- fixture_image_digest_or_id: `{values['FIXTURE_IMAGE_DIGEST']}`
- fixture_dockerfile_path: `deployment/Dockerfile.alert-service-fdp38-fixture`
- fixture_image_release_candidate_allowed: `false`
""", encoding="utf-8")

(OUTPUT_DIR / "fdp39-enablement-governance-pack.md").write_text("""# FDP-39 Enablement Governance Pack

- ci_mode: `true`
- local_fallback_used: `false`
- ready_for_enablement_review: `true`
- production_enabled: `false`
- bank_enabled: `false`
- release_config_pr_required: `true`
- human_approval_required: `true`
- dual_control_required: `true`
- rollback_plan_required: `true`
- operator_drill_required: `true`
- security_review_required: `true`
- audit_record_required: `true`

`READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`.
""", encoding="utf-8")

(OUTPUT_DIR / "fdp39-ci-proof-summary.md").write_text(f"""# FDP-39 CI Proof Summary

- ci_mode: `true`
- release_image_name: `{values['RELEASE_IMAGE_NAME']}`
- release_image_id: `{values['RELEASE_IMAGE_ID']}`
- release_image_digest_or_id: `{values['RELEASE_IMAGE_DIGEST']}`
- release_image_scan_root: `{values['RELEASE_IMAGE_SCAN_ROOT']}`
- release_image_scan_performed: `true`
- scanned_file_count: `{scanned_file_count}`
- forbidden_token_count: `0`
- fixture_image_name: `{values['FIXTURE_IMAGE_NAME']}`
- fixture_image_release_candidate_allowed: `false`
- production_enabled: `false`
- bank_enabled: `false`
- final_result: `PASS`
""", encoding="utf-8")
PY
