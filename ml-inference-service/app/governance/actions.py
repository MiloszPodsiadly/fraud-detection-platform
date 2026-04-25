from __future__ import annotations

from typing import Any


MAX_HISTORY_FOR_ACTIONS = 20
MAX_OPERATOR_ACTIONS = 5
STATUS_RANK = {"UNKNOWN": 0, "OK": 1, "WATCH": 2, "DRIFT": 3}
ACTION_SEVERITIES = ("NONE", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")


def recommend_drift_actions(
        model: dict[str, Any],
        drift: dict[str, Any],
        history_snapshots: list[dict[str, Any]] | None = None,
        model_lifecycle: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Translate drift into bounded advisory operator guidance."""
    history = list(history_snapshots or [])[:MAX_HISTORY_FOR_ACTIONS]
    trend = _trend_direction(history)
    severity = _operational_severity(drift, trend)
    recommendation = _recommendation(drift, severity)
    automation_policy = _automation_policy()
    _assert_advisory_only(automation_policy)
    return {
        "severity": severity,
        "confidence": str(drift.get("confidence", "LOW")),
        "drift_status": str(drift.get("status", "UNKNOWN")),
        "trend": trend,
        "recommended_actions": recommendation["recommended_actions"][:MAX_OPERATOR_ACTIONS],
        "escalation": recommendation["escalation"],
        "automation_policy": automation_policy,
        "evaluated_at": drift.get("evaluated_at"),
        "explanation": _explanation(drift, recommendation["explanation"]),
        "model_lifecycle": _model_lifecycle_context(model_lifecycle),
    }


def _recommendation(drift: dict[str, Any], severity: str) -> dict[str, Any]:
    status = str(drift.get("status", "UNKNOWN"))
    confidence = str(drift.get("confidence", "LOW"))
    reason = drift.get("reason")

    if status == "UNKNOWN":
        if reason == "reference_profile_unavailable":
            return _action(
                "CHECK_REFERENCE_PROFILE",
                "OPERATOR_REVIEW",
                "reference profile is unavailable; no aggregate drift change can be measured",
                [
                    "VERIFY_REFERENCE_PROFILE",
                    "CHECK_MODEL_VERSION_ALIGNMENT",
                    "KEEP_SCORING_UNCHANGED",
                ],
            )
        return _action(
            "COLLECT_MORE_DATA",
            "NONE",
            "runtime profile has insufficient observations for aggregate drift measurement",
            [
                "WAIT_FOR_MORE_OBSERVATIONS",
                "CONFIRM_PROFILE_NOT_RECENTLY_RESET",
                "KEEP_SCORING_UNCHANGED",
            ],
        )

    if status == "OK":
        return _action(
            "CONTINUE_MONITORING",
            "NONE",
            "aggregate drift signals remain within current thresholds",
            [
                "CONTINUE_GOVERNANCE_MONITORING",
                "NO_OPERATOR_ESCALATION_REQUIRED",
            ],
        )

    if status == "WATCH":
        return _action(
            "INVESTIGATE_DATA_SHIFT",
            "OPERATOR_REVIEW" if confidence in {"MEDIUM", "HIGH"} else "NONE",
            "aggregate drift signal crossed WATCH threshold",
            [
                "INSPECT_DRIFT_SUMMARY",
                "CHECK_RECENT_DEPLOYS_AND_FEATURE_CONTRACT",
                "KEEP_SCORING_UNCHANGED",
            ],
        )

    if status == "DRIFT" and confidence == "LOW":
        return _action(
            "INVESTIGATE_BASELINE_AND_DATA",
            "OPERATOR_REVIEW",
            "aggregate drift signal crossed DRIFT threshold with low confidence",
            [
                "CONFIRM_REFERENCE_PROFILE_QUALITY",
                "COMPARE_TRAFFIC_TO_REFERENCE_WINDOW",
                "KEEP_SCORING_UNCHANGED",
            ],
        )

    if status == "DRIFT":
        escalation = "MODEL_OWNER_REVIEW" if severity in {"HIGH", "CRITICAL"} else "OPERATOR_REVIEW"
        return _action(
            "ESCALATE_MODEL_REVIEW",
            escalation,
            "aggregate drift signal crossed DRIFT threshold",
            [
                "OPEN_MODEL_DATA_REVIEW",
                "VALIDATE_FEATURE_GENERATION_AND_TRAFFIC_MIX",
                "KEEP_SCORING_UNCHANGED",
            ],
        )

    return _action(
        "CONTINUE_MONITORING",
        "NONE",
        "aggregate drift state has no advisory escalation",
        ["CONTINUE_GOVERNANCE_MONITORING"],
    )


def _action(action: str, escalation: str, explanation: str, operator_actions: list[str]) -> dict[str, Any]:
    return {
        "escalation": escalation,
        "explanation": explanation,
        "recommended_actions": [action, *operator_actions][:MAX_OPERATOR_ACTIONS],
    }


def _operational_severity(drift: dict[str, Any], trend: str) -> str:
    status = str(drift.get("status", "UNKNOWN"))
    confidence = str(drift.get("confidence", "LOW"))
    if status == "UNKNOWN":
        return "INFO"
    if status == "OK":
        return "NONE"
    if status == "WATCH":
        return "MEDIUM" if confidence in {"MEDIUM", "HIGH"} else "LOW"
    if status == "DRIFT":
        if confidence == "LOW":
            return "MEDIUM"
        if trend == "INCREASING":
            return "CRITICAL"
        return "HIGH"
    return "INFO"


def _trend_direction(history: list[dict[str, Any]]) -> str:
    statuses = [str(item.get("driftStatus", "UNKNOWN")) for item in history if isinstance(item, dict)]
    if len(statuses) < 2:
        return "STABLE"
    newest = statuses[0]
    oldest = statuses[-1]
    newest_rank = STATUS_RANK.get(newest, 0)
    oldest_rank = STATUS_RANK.get(oldest, 0)
    if newest_rank > oldest_rank:
        return "INCREASING"
    if newest_rank < oldest_rank:
        return "DECREASING"
    return "STABLE"


def _explanation(drift: dict[str, Any], fallback: str) -> str:
    signal = _primary_signal(drift.get("signals"))
    if not signal:
        return fallback

    subject = _signal_subject(str(signal.get("drift_type", "")), str(signal.get("statistic", "value")))
    reference = _float(signal.get("reference"))
    inference = _float(signal.get("inference"))
    direction = "increased" if inference >= reference else "decreased"
    relative_difference = signal.get("relative_difference")
    if isinstance(relative_difference, (int, float)):
        amount = f"{abs(relative_difference) * 100:.0f}%"
    else:
        amount = f"{_float(signal.get('absolute_difference')):.3f}"
    return f"{subject} {direction} by {amount} compared to reference profile"


def _primary_signal(signals: Any) -> dict[str, Any] | None:
    if not isinstance(signals, list):
        return None
    for severity in ("DRIFT", "WATCH"):
        for signal in signals:
            if isinstance(signal, dict) and str(signal.get("severity")) == severity:
                return signal
    return None


def _signal_subject(drift_type: str, statistic: str) -> str:
    if drift_type.startswith("score_"):
        return f"score {statistic}"
    if drift_type == "high_risk_rate_shift":
        return "high-risk rate"
    if drift_type == "missing_feature_rate":
        return "missing feature rate"
    if drift_type.startswith("feature_"):
        return f"feature {statistic}"
    return "aggregate signal"


def _automation_policy() -> dict[str, bool]:
    return {
        "advisory_only": True,
        "affects_scoring": False,
        "blocks_requests": False,
        "switches_model": False,
        "triggers_retraining": False,
    }


def _model_lifecycle_context(model_lifecycle: dict[str, Any] | None) -> dict[str, Any]:
    context = model_lifecycle if isinstance(model_lifecycle, dict) else {}
    return {
        "current_model_version": context.get("current_model_version"),
        "model_loaded_at": context.get("model_loaded_at"),
        "model_changed_recently": bool(context.get("model_changed_recently", False)),
        "recent_lifecycle_event_count": int(context.get("recent_lifecycle_event_count") or 0),
    }


def _assert_advisory_only(policy: dict[str, bool]) -> None:
    unsafe_flags = ("affects_scoring", "blocks_requests", "switches_model", "triggers_retraining")
    if policy.get("advisory_only") is not True or any(policy.get(flag) for flag in unsafe_flags):
        raise RuntimeError("governance drift actions must remain advisory-only")


def _float(value: Any) -> float:
    if isinstance(value, bool) or value is None:
        return 0.0
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0
