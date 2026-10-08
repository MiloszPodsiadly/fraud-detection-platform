from __future__ import annotations

import json
import math
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any

from app.model_identity_policy import (
    ARTIFACT_SHA256_PATTERN,
    ModelLogicalIdentity,
)

if TYPE_CHECKING:
    from app.inference.model_runtime import ResolvedModelRuntime


REFERENCE_PROFILE_PATH = Path(__file__).with_name("reference_profile.local.json")
RISK_LEVELS = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
REFERENCE_QUALITY_BY_SOURCE = {
    "synthetic": "SYNTHETIC",
    "training": "LIMITED",
    "evaluation": "LIMITED",
}


class NumericProfile:
    """Bounded aggregate numeric stats with fixed histogram buckets."""

    def __init__(self, bucket_count: int = 20) -> None:
        self.bucket_count = bucket_count
        self.count = 0
        self.total = 0.0
        self.sum_squares = 0.0
        self.minimum: float | None = None
        self.maximum: float | None = None
        self.buckets = [0 for _ in range(bucket_count)]

    def update(self, value: float) -> None:
        clipped = max(min(float(value), 1.0), 0.0)
        self.count += 1
        self.total += clipped
        self.sum_squares += clipped * clipped
        self.minimum = clipped if self.minimum is None else min(self.minimum, clipped)
        self.maximum = clipped if self.maximum is None else max(self.maximum, clipped)
        index = min(int(clipped * self.bucket_count), self.bucket_count - 1)
        self.buckets[index] += 1

    def snapshot(self) -> dict[str, Any]:
        if self.count == 0:
            return {
                "count": 0,
                "mean": 0.0,
                "std": 0.0,
                "min": 0.0,
                "max": 0.0,
                "p50": 0.0,
                "p90": 0.0,
                "p95": 0.0,
            }
        mean = self.total / self.count
        variance = max((self.sum_squares / self.count) - (mean * mean), 0.0)
        return {
            "count": self.count,
            "mean": round(mean, 6),
            "std": round(math.sqrt(variance), 6),
            "min": round(float(self.minimum or 0.0), 6),
            "max": round(float(self.maximum or 0.0), 6),
            "p50": round(self._percentile(0.50), 6),
            "p90": round(self._percentile(0.90), 6),
            "p95": round(self._percentile(0.95), 6),
        }

    def _percentile(self, quantile: float) -> float:
        target = max(math.ceil(self.count * quantile), 1)
        seen = 0
        for index, bucket_count in enumerate(self.buckets):
            seen += bucket_count
            if seen >= target:
                return (index + 0.5) / self.bucket_count
        return 1.0


class InferenceProfile:
    """Process-local aggregate inference profile; no raw requests are retained."""

    def __init__(self, resolved_runtime: ResolvedModelRuntime, feature_names: list[str]) -> None:
        self.model_name = resolved_runtime.logical_identity.model_name
        self.model_version = resolved_runtime.logical_identity.model_version
        self.artifact_sha256 = resolved_runtime.artifact_sha256
        self.selection_source = resolved_runtime.selection_source.value
        self.feature_names = list(feature_names)
        self._lock = threading.Lock()
        self.reset()

    def reset(self) -> None:
        now = datetime.now(timezone.utc)
        with getattr(self, "_lock", threading.Lock()):
            self.started_at = now
            self.last_updated_at: str | None = None
            self.observations = 0
            self.feature_stats = {name: NumericProfile() for name in self.feature_names}
            self.missing_feature_counts = {name: 0 for name in self.feature_names}
            self.invalid_feature_counts = {name: 0 for name in self.feature_names}
            self.score_stats = NumericProfile()
            self.risk_level_counts = {level: 0 for level in RISK_LEVELS}

    def update(self, normalized_features: dict[str, Any], fraud_score: Any, risk_level: Any) -> None:
        with self._lock:
            self.observations += 1
            self.last_updated_at = _utc_now()
            for feature_name in self.feature_names:
                if feature_name not in normalized_features:
                    self.missing_feature_counts[feature_name] += 1
                    continue
                value = _number(normalized_features.get(feature_name))
                if value is None:
                    self.invalid_feature_counts[feature_name] += 1
                    continue
                self.feature_stats[feature_name].update(value)

            score = _number(fraud_score)
            if score is not None:
                self.score_stats.update(score)
            level = str(risk_level)
            if level in self.risk_level_counts:
                self.risk_level_counts[level] += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            profile_started_at = self.started_at.isoformat(timespec="seconds")
            return {
                "profileType": "process_lifetime_inference",
                "model_name": self.model_name,
                "model_version": self.model_version,
                "artifact_sha256": self.artifact_sha256,
                "selection_source": self.selection_source,
                "profile_started_at": profile_started_at,
                "last_updated_at": self.last_updated_at,
                "observation_count": self.observations,
                "profile_uptime_seconds": self._uptime_seconds(),
                "window": {
                    "type": "process_lifetime_in_memory",
                    "started_at": profile_started_at,
                    "last_updated_at": self.last_updated_at,
                    "reset_behavior": "reset on process restart or explicit in-process test reset",
                },
                "observations": self.observations,
                "numeric_feature_stats": {
                    name: self.feature_stats[name].snapshot()
                    for name in self.feature_names
                },
                "missing_feature_counts": dict(self.missing_feature_counts),
                "invalid_feature_counts": dict(self.invalid_feature_counts),
                "score_distribution": self.score_stats.snapshot(),
                "risk_level_distribution": self._risk_distribution(),
                "privacy": "aggregate numeric stats only; raw requests and identifiers are not retained",
            }

    def _uptime_seconds(self) -> int:
        return max(int((datetime.now(timezone.utc) - self.started_at).total_seconds()), 0)

    def _risk_distribution(self) -> dict[str, float]:
        if self.observations == 0:
            return {level: 0.0 for level in RISK_LEVELS}
        return {
            level: round(count / self.observations, 6)
            for level, count in self.risk_level_counts.items()
        }


def load_reference_profile(
        resolved_runtime: ResolvedModelRuntime,
        path: Path = REFERENCE_PROFILE_PATH,
) -> dict[str, Any]:
    if not path.exists():
        return _unavailable_reference_profile("missing", path, "REFERENCE_PROFILE_MISSING")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return _unavailable_reference_profile(
            "invalid",
            path,
            "REFERENCE_PROFILE_INVALID",
            error=exc.__class__.__name__,
        )
    if not isinstance(payload, dict):
        return _unavailable_reference_profile("invalid", path, "REFERENCE_PROFILE_INVALID")
    normalized = _normalize_reference_profile(payload)
    return _bind_reference_profile(normalized, resolved_runtime, path)


def governance_model_metadata(resolved_runtime: Any) -> dict[str, Any]:
    artifact = resolved_runtime.validated_artifact.artifact
    artifact_path = resolved_runtime.canonical_artifact_path_or_id
    training = artifact.get("training") if isinstance(artifact.get("training"), dict) else {}
    feature_set = _string_list(artifact.get("featureSetUsed")) or _string_list(training.get("featureSetUsed"))
    if not feature_set:
        feature_set = _string_list(artifact.get("featureSchema")) or _string_list(artifact.get("weights"))
    return {
        "model_name": resolved_runtime.logical_identity.model_name,
        "model_version": resolved_runtime.logical_identity.model_version,
        "model_family": resolved_runtime.artifact_identity.model_family,
        "training_mode": str(artifact.get("trainingMode") or training.get("trainingMode") or "production"),
        "feature_set": feature_set,
        "artifact": {
            "path": str(artifact_path),
            "loaded": True,
            "sha256": resolved_runtime.artifact_sha256,
            "selection_source": resolved_runtime.selection_source.value,
            "training_source": training.get("source"),
            "training_examples": training.get("examples"),
            "algorithm": training.get("algorithm"),
        },
        "governance": {
            "capabilities": [
                "model_lineage",
                "reference_profile",
                "inference_profile",
                "threshold_drift_detection",
            ],
            "scope": "additive runtime oversight; scoring semantics unchanged",
        },
    }


def reference_feature_names(reference_profile: dict[str, Any]) -> list[str]:
    if not reference_profile.get("available"):
        return []
    stats = reference_profile.get("numeric_feature_stats")
    if not isinstance(stats, dict):
        return []
    return [str(name) for name in stats.keys()]


def reference_profile_summary(reference_profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "available": bool(reference_profile.get("available")),
        "status": reference_profile.get("status"),
        "profileVersion": reference_profile.get("profileVersion"),
        "reference_quality": reference_profile.get("reference_quality"),
        "source": reference_profile.get("source"),
        "data_window": reference_profile.get("data_window"),
        "generated_by": reference_profile.get("generated_by"),
        "sample_size": reference_profile.get("sample_size"),
        "model_name": reference_profile.get("model_name"),
        "model_version": reference_profile.get("model_version"),
        "artifact_sha256": reference_profile.get("artifact_sha256"),
        "identity_status": reference_profile.get("identity_status"),
        "unavailable_reason": reference_profile.get("unavailable_reason"),
    }


def inference_profile_summary(inference_profile: dict[str, Any]) -> dict[str, Any]:
    return {
        "profileType": inference_profile.get("profileType"),
        "model_name": inference_profile.get("model_name"),
        "model_version": inference_profile.get("model_version"),
        "artifact_sha256": inference_profile.get("artifact_sha256"),
        "selection_source": inference_profile.get("selection_source"),
        "profile_started_at": inference_profile.get("profile_started_at"),
        "last_updated_at": inference_profile.get("last_updated_at"),
        "observation_count": inference_profile.get("observation_count", inference_profile.get("observations", 0)),
        "profile_uptime_seconds": inference_profile.get("profile_uptime_seconds", 0),
        "reset_behavior": "profile resets on process restart",
    }


def governance_response(
        model: dict[str, Any],
        reference_profile: dict[str, Any],
        inference_profile: dict[str, Any],
        drift: dict[str, Any] | None = None,
        include_reference_details: bool = False,
        include_inference_details: bool = False,
) -> dict[str, Any]:
    drift_payload = drift or {
        "status": "UNKNOWN",
        "confidence": "LOW",
        "signals": [],
        "evaluated_at": None,
    }
    return {
        "model": model,
        "reference_profile": reference_profile if include_reference_details else reference_profile_summary(reference_profile),
        "inference_profile": inference_profile if include_inference_details else inference_profile_summary(inference_profile),
        "drift": drift_payload,
    }


def _normalize_reference_profile(payload: dict[str, Any]) -> dict[str, Any]:
    normalized = dict(payload)
    raw_source = normalized.get("source", "synthetic")
    if isinstance(raw_source, dict):
        normalized.setdefault("source_metadata", raw_source)
        raw_source = raw_source.get("type", "synthetic")
    source = str(raw_source).lower()
    if source == "synthetic_training_pipeline":
        source = "synthetic"
    if source not in {"synthetic", "training", "evaluation"}:
        source = "synthetic"
    normalized["source"] = source
    normalized["reference_quality"] = str(
        normalized.get("reference_quality") or REFERENCE_QUALITY_BY_SOURCE.get(source, "SYNTHETIC")
    )
    normalized["sample_size"] = int(normalized.get("sample_size") or _sample_size(normalized))
    normalized.setdefault("data_window", "deterministic local synthetic training sample")
    normalized.setdefault("generated_by", "ml-inference-service synthetic fraud behavior pipeline")
    return normalized


def _bind_reference_profile(
        profile: dict[str, Any],
        resolved_runtime: ResolvedModelRuntime,
        path: Path,
) -> dict[str, Any]:
    model_name = profile.get("model_name")
    model_version = profile.get("model_version")
    try:
        profile_identity = ModelLogicalIdentity(model_name, model_version)
    except (TypeError, ValueError):
        return _unavailable_reference_profile(
            "UNKNOWN",
            path,
            "REFERENCE_LOGICAL_IDENTITY_MISSING_OR_INVALID",
            profile,
        )
    if profile_identity != resolved_runtime.logical_identity:
        return _unavailable_reference_profile(
            "IDENTITY_MISMATCH",
            path,
            "REFERENCE_LOGICAL_IDENTITY_MISMATCH",
            profile,
            identity_status="MISMATCH",
        )

    artifact_sha256 = profile.get("artifact_sha256")
    if not isinstance(artifact_sha256, str) or ARTIFACT_SHA256_PATTERN.fullmatch(artifact_sha256) is None:
        return _unavailable_reference_profile(
            "UNKNOWN",
            path,
            "REFERENCE_ARTIFACT_SHA256_MISSING_OR_INVALID",
            profile,
        )
    if artifact_sha256 != resolved_runtime.artifact_sha256:
        return _unavailable_reference_profile(
            "IDENTITY_MISMATCH",
            path,
            "REFERENCE_ARTIFACT_SHA256_MISMATCH",
            profile,
            identity_status="MISMATCH",
        )
    return {
        **profile,
        "available": True,
        "status": "loaded",
        "identity_status": "MATCH",
        "path": path.name,
    }


def _unavailable_reference_profile(
        status: str,
        path: Path,
        reason: str,
        profile: dict[str, Any] | None = None,
        *,
        identity_status: str = "UNKNOWN",
        error: str | None = None,
) -> dict[str, Any]:
    result = {
        **(profile or {}),
        "available": False,
        "status": status,
        "identity_status": identity_status,
        "unavailable_reason": reason,
        "path": path.name,
        "reference_quality": (profile or {}).get("reference_quality", "UNKNOWN"),
        "drift_status": "UNKNOWN",
    }
    if error is not None:
        result["error"] = error
    return result


def _sample_size(profile: dict[str, Any]) -> int:
    value = profile.get("score_distribution")
    if isinstance(value, dict):
        try:
            return int(value.get("count") or 0)
        except (TypeError, ValueError):
            return 0
    return 0


def _string_list(value: Any) -> list[str]:
    if isinstance(value, dict):
        return [str(name) for name in value.keys()]
    if isinstance(value, list):
        return [str(name) for name in value if isinstance(name, str)]
    return []


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
