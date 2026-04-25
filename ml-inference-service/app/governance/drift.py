from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


MIN_OBSERVATIONS = 100
STATUS_ORDER = {"OK": 0, "WATCH": 1, "DRIFT": 2}
CONFIDENCE_ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def evaluate_drift(
        reference_profile: dict[str, Any],
        inference_profile: dict[str, Any],
        min_observations: int = MIN_OBSERVATIONS,
) -> dict[str, Any]:
    model_name = reference_profile.get("model_name") or inference_profile.get("model_name")
    model_version = reference_profile.get("model_version") or inference_profile.get("model_version")
    observations = int(inference_profile.get("observation_count", inference_profile.get("observations")) or 0)
    evaluated_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
    profile_status = _inference_profile_status(inference_profile, observations, min_observations)
    reference_quality = str(reference_profile.get("reference_quality") or "UNKNOWN")

    if not reference_profile.get("available"):
        return _unknown(
            model_name,
            model_version,
            observations,
            min_observations,
            evaluated_at,
            "reference_profile_unavailable",
            profile_status,
            reference_quality,
        )
    if observations < min_observations:
        return _unknown(
            model_name,
            model_version,
            observations,
            min_observations,
            evaluated_at,
            "insufficient_data",
            profile_status,
            reference_quality,
        )

    signals: list[dict[str, Any]] = []
    reference_features = reference_profile.get("numeric_feature_stats")
    inference_features = inference_profile.get("numeric_feature_stats")
    if isinstance(reference_features, dict) and isinstance(inference_features, dict):
        for feature_name in sorted(reference_features):
            reference_stats = reference_features.get(feature_name)
            inference_stats = inference_features.get(feature_name)
            if not isinstance(reference_stats, dict) or not isinstance(inference_stats, dict):
                continue
            signals.append(_numeric_signal("feature_mean_shift", feature_name, reference_stats, inference_stats, "mean"))
            signals.append(_numeric_signal("feature_p95_shift", feature_name, reference_stats, inference_stats, "p95"))
            signals.append(_missing_signal(feature_name, inference_profile, observations))

    reference_score = reference_profile.get("score_distribution")
    inference_score = inference_profile.get("score_distribution")
    if isinstance(reference_score, dict) and isinstance(inference_score, dict):
        signals.append(_numeric_signal("score_mean_shift", "fraudScore", reference_score, inference_score, "mean"))
        signals.append(_numeric_signal("score_p95_shift", "fraudScore", reference_score, inference_score, "p95"))
        signals.append(_rate_signal(
            "high_risk_rate_shift",
            "riskLevel",
            _high_risk_rate(reference_profile),
            _high_risk_rate(inference_profile),
        ))

    status = _global_status(signals)
    confidence = _confidence_level(observations, min_observations, inference_profile, reference_quality)
    return {
        "status": status,
        "confidence": confidence,
        "confidence_level": confidence,
        "model_name": model_name,
        "model_version": model_version,
        "reference_profile_version": reference_profile.get("profileVersion"),
        "reference_quality": reference_quality,
        "evaluated_at": evaluated_at,
        "sample_size": observations,
        "observation_count": observations,
        "min_observations": min_observations,
        "inference_profile_status": profile_status,
        "reason": None if confidence != "LOW" else _low_confidence_reason(reference_quality),
        "signals": signals[:64],
        "thresholds": {
            "watch_abs_diff": 0.15,
            "drift_abs_diff": 0.35,
            "watch_z_score": 2.0,
            "drift_z_score": 3.0,
            "watch_missing_rate": 0.01,
            "drift_missing_rate": 0.05,
        },
        "limitations": [
            "simple threshold checks only",
            "no p-value statistical testing",
            "non-production reference profiles downgrade drift confidence",
            "drift does not block scoring or change fraud decisions",
        ],
    }


def _unknown(
        model_name: Any,
        model_version: Any,
        observations: int,
        min_observations: int,
        evaluated_at: str,
        reason: str,
        inference_profile_status: str,
        reference_quality: str,
) -> dict[str, Any]:
    return {
        "status": "UNKNOWN",
        "confidence": "LOW",
        "confidence_level": "LOW",
        "model_name": model_name,
        "model_version": model_version,
        "reference_quality": reference_quality,
        "evaluated_at": evaluated_at,
        "sample_size": observations,
        "observation_count": observations,
        "min_observations": min_observations,
        "inference_profile_status": inference_profile_status,
        "reason": reason,
        "signals": [],
    }


def _numeric_signal(
        drift_type: str,
        name: str,
        reference_stats: dict[str, Any],
        inference_stats: dict[str, Any],
        statistic: str,
) -> dict[str, Any]:
    reference_value = _float(reference_stats.get(statistic))
    inference_value = _float(inference_stats.get(statistic))
    diff = inference_value - reference_value
    abs_diff = abs(diff)
    reference_std = max(_float(reference_stats.get("std")), 0.0)
    z_score = abs_diff / reference_std if reference_std > 0 else None
    severity = _severity(abs_diff, z_score)
    return {
        "drift_type": drift_type,
        "name": name,
        "statistic": statistic,
        "reference": round(reference_value, 6),
        "inference": round(inference_value, 6),
        "absolute_difference": round(abs_diff, 6),
        "relative_difference": _relative_difference(reference_value, inference_value),
        "z_score": round(z_score, 6) if z_score is not None else None,
        "severity": severity,
    }


def _missing_signal(feature_name: str, inference_profile: dict[str, Any], observations: int) -> dict[str, Any]:
    missing_counts = inference_profile.get("missing_feature_counts")
    invalid_counts = inference_profile.get("invalid_feature_counts")
    missing = int(missing_counts.get(feature_name, 0)) if isinstance(missing_counts, dict) else 0
    invalid = int(invalid_counts.get(feature_name, 0)) if isinstance(invalid_counts, dict) else 0
    rate = (missing + invalid) / max(observations, 1)
    if rate >= 0.05:
        severity = "DRIFT"
    elif rate >= 0.01:
        severity = "WATCH"
    else:
        severity = "OK"
    return {
        "drift_type": "missing_feature_rate",
        "name": feature_name,
        "statistic": "missing_or_invalid_rate",
        "reference": 0.0,
        "inference": round(rate, 6),
        "absolute_difference": round(rate, 6),
        "relative_difference": None,
        "z_score": None,
        "severity": severity,
    }


def _rate_signal(drift_type: str, name: str, reference_rate: float, inference_rate: float) -> dict[str, Any]:
    diff = abs(inference_rate - reference_rate)
    if diff >= 0.25:
        severity = "DRIFT"
    elif diff >= 0.10:
        severity = "WATCH"
    else:
        severity = "OK"
    return {
        "drift_type": drift_type,
        "name": name,
        "statistic": "rate",
        "reference": round(reference_rate, 6),
        "inference": round(inference_rate, 6),
        "absolute_difference": round(diff, 6),
        "relative_difference": _relative_difference(reference_rate, inference_rate),
        "z_score": None,
        "severity": severity,
    }


def _severity(abs_diff: float, z_score: float | None) -> str:
    if abs_diff >= 0.35 or (z_score is not None and z_score >= 3.0):
        return "DRIFT"
    if abs_diff >= 0.15 or (z_score is not None and z_score >= 2.0):
        return "WATCH"
    return "OK"


def _global_status(signals: list[dict[str, Any]]) -> str:
    if not signals:
        return "UNKNOWN"
    worst = max(STATUS_ORDER.get(str(signal.get("severity")), 0) for signal in signals)
    for status, value in STATUS_ORDER.items():
        if value == worst:
            return status
    return "OK"


def _inference_profile_status(profile: dict[str, Any], observations: int, min_observations: int) -> str:
    uptime_seconds = int(profile.get("profile_uptime_seconds") or 0)
    if observations >= min_observations:
        return "STABLE"
    if uptime_seconds < 300:
        return "RESET_RECENTLY"
    return "FRESH"


def _confidence_level(
        observations: int,
        min_observations: int,
        inference_profile: dict[str, Any],
        reference_quality: str,
) -> str:
    if observations < min_observations:
        return "LOW"
    if observations < min_observations * 2:
        confidence = "LOW"
    elif observations < min_observations * 5:
        confidence = "MEDIUM"
    else:
        confidence = "HIGH" if _variance_stable(inference_profile) else "MEDIUM"
    return _downgrade_confidence(confidence, reference_quality)


def _variance_stable(inference_profile: dict[str, Any]) -> bool:
    score_distribution = inference_profile.get("score_distribution")
    if not isinstance(score_distribution, dict) or _float(score_distribution.get("std")) <= 0.0:
        return False
    feature_stats = inference_profile.get("numeric_feature_stats")
    if not isinstance(feature_stats, dict):
        return False
    return any(isinstance(stats, dict) and _float(stats.get("std")) > 0.0 for stats in feature_stats.values())


def _downgrade_confidence(confidence: str, reference_quality: str) -> str:
    if reference_quality == "PRODUCTION":
        return confidence
    max_confidence = "MEDIUM" if reference_quality == "LIMITED" else "LOW"
    return confidence if CONFIDENCE_ORDER[confidence] <= CONFIDENCE_ORDER[max_confidence] else max_confidence


def _low_confidence_reason(reference_quality: str) -> str | None:
    if reference_quality != "PRODUCTION":
        return "non_production_reference_profile"
    return None


def _high_risk_rate(profile: dict[str, Any]) -> float:
    score_distribution = profile.get("score_distribution")
    if isinstance(score_distribution, dict) and "high_risk_rate" in score_distribution:
        return _float(score_distribution.get("high_risk_rate"))
    risk_distribution = profile.get("risk_level_distribution")
    if isinstance(risk_distribution, dict):
        return _float(risk_distribution.get("HIGH")) + _float(risk_distribution.get("CRITICAL"))
    return 0.0


def _relative_difference(reference: float, inference: float) -> float | None:
    if reference == 0.0:
        return None
    return round((inference - reference) / abs(reference), 6)


def _float(value: Any) -> float:
    if isinstance(value, bool) or value is None:
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0
