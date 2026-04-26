from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from threading import Lock
from typing import Any

from app.governance.actions import MAX_OPERATOR_ACTIONS
from app.governance.persistence import _database_name, _json_safe, _new_mongo_client, _positive_int


ADVISORY_EVENT_TYPE = "GOVERNANCE_DRIFT_ADVISORY"
DEFAULT_ADVISORY_COLLECTION = "ml_governance_advisory_events"
DEFAULT_ADVISORY_RETENTION_LIMIT = 200
MAX_ADVISORY_LIMIT = 100
ADVISORY_DEDUP_WINDOW = timedelta(minutes=5)
EMITTING_SEVERITIES = {"HIGH", "CRITICAL"}
NON_EMITTING_SEVERITIES = {"NONE", "INFO", "LOW"}
ADVISORY_SEVERITIES = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
ADVISORY_CONFIDENCE_CONTEXTS = {"LOW_SAMPLE", "PARTIAL_DATA", "STABLE_BASELINE", "SUFFICIENT_DATA"}


class AdvisoryEventRepository:
    """Storage seam for governance advisory events."""

    def persist(self, event: dict[str, Any]) -> None:
        raise NotImplementedError

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise NotImplementedError

    def available(self) -> bool:
        raise NotImplementedError


class UnavailableAdvisoryEventRepository(AdvisoryEventRepository):
    def persist(self, event: dict[str, Any]) -> None:
        raise RuntimeError("governance advisory persistence unavailable")

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise RuntimeError("governance advisory persistence unavailable")

    def available(self) -> bool:
        return False


class MongoAdvisoryEventRepository(AdvisoryEventRepository):
    """Optional MongoDB-backed advisory event repository with bounded retention."""

    def __init__(
            self,
            mongodb_uri: str,
            collection_name: str,
            retention_limit: int,
            client: Any | None = None,
    ) -> None:
        self.mongodb_uri = mongodb_uri
        self.collection_name = collection_name
        self.retention_limit = retention_limit
        self._client = client
        self._collection: Any | None = None
        self._indexes_ready = False

    def persist(self, event: dict[str, Any]) -> None:
        collection = self._get_collection()
        collection.insert_one(dict(event))
        self._enforce_retention(
            model_name=str(event.get("model_name")),
            model_version=str(event.get("model_version")),
        )

    def history(self, limit: int) -> list[dict[str, Any]]:
        collection = self._get_collection()
        cursor = collection.find({}, {"_id": 0}).sort("created_at", -1).limit(limit)
        return [_json_safe(document) for document in cursor]

    def available(self) -> bool:
        try:
            self._get_collection()
            return True
        except Exception:
            return False

    def _get_collection(self) -> Any:
        if self._collection is None:
            client = self._client or _new_mongo_client(self.mongodb_uri)
            database = client.get_default_database() if hasattr(client, "get_default_database") else None
            if database is None:
                database = client[_database_name(self.mongodb_uri)]
            self._collection = database[self.collection_name]
        if not self._indexes_ready:
            self._ensure_indexes()
            self._indexes_ready = True
        return self._collection

    def _ensure_indexes(self) -> None:
        collection = self._collection
        collection.create_index([("created_at", -1)], background=True)
        collection.create_index([("severity", 1)], background=True)
        collection.create_index([("model_name", 1), ("model_version", 1)], background=True)

    def _enforce_retention(self, model_name: str, model_version: str) -> None:
        if self.retention_limit <= 0:
            return
        collection = self._get_collection()
        query = {"model_name": model_name, "model_version": model_version}
        excess = collection.count_documents(query) - self.retention_limit
        if excess <= 0:
            return
        stale = collection.find(query, {"_id": 1}).sort("created_at", 1).limit(excess)
        stale_ids = [document["_id"] for document in stale if "_id" in document]
        if stale_ids:
            collection.delete_many({"_id": {"$in": stale_ids}})


class AdvisoryPersistenceConfig:
    def __init__(self, mongodb_uri: str, collection: str, retention_limit: int) -> None:
        self.mongodb_uri = mongodb_uri
        self.collection = collection
        self.retention_limit = retention_limit

    @staticmethod
    def from_env() -> AdvisoryPersistenceConfig:
        return AdvisoryPersistenceConfig(
            mongodb_uri=os.getenv("MONGODB_URI", "mongodb://mongodb:27017/fraud_governance"),
            collection=os.getenv("GOVERNANCE_ADVISORY_COLLECTION", DEFAULT_ADVISORY_COLLECTION),
            retention_limit=_positive_int(
                os.getenv("GOVERNANCE_ADVISORY_RETENTION_LIMIT"),
                DEFAULT_ADVISORY_RETENTION_LIMIT,
            ),
        )


class AdvisoryEventService:
    """Coordinates advisory event emission without affecting scoring or drift evaluation."""

    def __init__(
            self,
            repository: AdvisoryEventRepository,
            config: AdvisoryPersistenceConfig,
    ) -> None:
        self.repository = repository
        self.config = config
        self._events: list[dict[str, Any]] = []
        self._lock = Lock()

    def emit_if_needed(
            self,
            actions: dict[str, Any],
            model: dict[str, Any],
            lifecycle_context: dict[str, Any],
            drift_context: dict[str, Any] | None = None,
    ) -> tuple[dict[str, Any] | None, str]:
        if not should_emit_advisory(actions):
            return None, "skipped"
        fingerprint = advisory_fingerprint(actions, model)
        now = datetime.now(timezone.utc)
        if self._recent_duplicate(fingerprint, now):
            return None, "deduplicated"
        event = build_advisory_event(actions, model, lifecycle_context, drift_context, created_at=now)
        self._append_memory(event)
        try:
            self.repository.persist(event)
            return event, "persisted"
        except Exception:
            return event, "memory"

    def history_response(
            self,
            limit: int | None = None,
            severity: str | None = None,
            model_version: str | None = None,
    ) -> dict[str, Any]:
        bounded_limit = self._bounded_limit(limit)
        query_limit = max(min(self.config.retention_limit, MAX_ADVISORY_LIMIT), bounded_limit)
        try:
            events = self.repository.history(query_limit)
            status = "AVAILABLE"
        except Exception:
            events = self.memory_history(query_limit)
            status = "PARTIAL" if events else "UNAVAILABLE"
        events = self._apply_filters(events, severity, model_version)[:bounded_limit]
        return {
            "status": status,
            "count": len(events),
            "retention_limit": self.config.retention_limit,
            "advisory_events": events,
        }

    def memory_history(self, limit: int | None = None) -> list[dict[str, Any]]:
        bounded_limit = self._bounded_limit(limit)
        with self._lock:
            return [dict(event) for event in reversed(self._events[-bounded_limit:])]

    def _append_memory(self, event: dict[str, Any]) -> None:
        with self._lock:
            self._events.append(dict(event))
            if len(self._events) > self.config.retention_limit:
                self._events = self._events[-self.config.retention_limit:]

    def _recent_duplicate(self, fingerprint: tuple[str, str, str, str], now: datetime) -> bool:
        return self._recent_memory_duplicate(fingerprint, now) or self._recent_repository_duplicate(fingerprint, now)

    def _recent_memory_duplicate(self, fingerprint: tuple[str, str, str, str], now: datetime) -> bool:
        with self._lock:
            events = list(self._events)
        return any(_matches_recent(event, fingerprint, now) for event in events)

    def _recent_repository_duplicate(self, fingerprint: tuple[str, str, str, str], now: datetime) -> bool:
        try:
            events = self.repository.history(MAX_ADVISORY_LIMIT)
        except Exception:
            return False
        return any(_matches_recent(event, fingerprint, now) for event in events)

    def _apply_filters(
            self,
            events: list[dict[str, Any]],
            severity: str | None,
            model_version: str | None,
    ) -> list[dict[str, Any]]:
        filtered = list(events)
        if severity in ADVISORY_SEVERITIES:
            filtered = [event for event in filtered if event.get("severity") == severity]
        if _valid_model_version_filter(model_version):
            filtered = [event for event in filtered if event.get("model_version") == model_version]
        return filtered

    def _bounded_limit(self, limit: int | None) -> int:
        requested = self.config.retention_limit if limit is None else limit
        return max(min(requested, MAX_ADVISORY_LIMIT, self.config.retention_limit), 1)


def should_emit_advisory(actions: dict[str, Any]) -> bool:
    severity = str(actions.get("severity", "INFO"))
    confidence = str(actions.get("confidence", "LOW"))
    escalation = str(actions.get("escalation", "NONE"))
    if severity in NON_EMITTING_SEVERITIES:
        return False
    if confidence == "LOW":
        return False
    return severity in EMITTING_SEVERITIES or escalation != "NONE"


def build_advisory_event(
        actions: dict[str, Any],
        model: dict[str, Any],
        lifecycle_context: dict[str, Any],
        drift_context: dict[str, Any] | None = None,
        created_at: datetime | None = None,
) -> dict[str, Any]:
    severity = str(actions.get("severity", "INFO"))
    if severity not in ADVISORY_SEVERITIES:
        severity = "LOW"
    now = created_at or datetime.now(timezone.utc)
    return {
        "event_id": str(uuid.uuid4()),
        "event_type": ADVISORY_EVENT_TYPE,
        "severity": severity,
        "drift_status": str(actions.get("drift_status", "UNKNOWN")),
        "confidence": str(actions.get("confidence", "LOW")),
        "advisory_confidence_context": advisory_confidence_context(actions, drift_context or {}),
        "model_name": model.get("model_name"),
        "model_version": model.get("model_version"),
        "lifecycle_context": _bounded_lifecycle_context(lifecycle_context),
        "recommended_actions": _recommended_actions(actions.get("recommended_actions")),
        "explanation": _bounded_explanation(actions.get("explanation")),
        "created_at": now.isoformat(timespec="seconds"),
    }


def advisory_fingerprint(actions: dict[str, Any], model: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        str(model.get("model_name")),
        str(model.get("model_version")),
        str(actions.get("severity", "INFO")),
        str(actions.get("drift_status", "UNKNOWN")),
    )


def advisory_confidence_context(actions: dict[str, Any], drift_context: dict[str, Any]) -> str:
    observations = _int_or_none(drift_context.get("observation_count", drift_context.get("sample_size")))
    min_observations = _int_or_none(drift_context.get("min_observations"))
    if observations is not None and min_observations is not None and observations < min_observations:
        return "LOW_SAMPLE"
    if (
            drift_context.get("reason") == "reference_profile_unavailable"
            or drift_context.get("inference_profile_status") in {"RESET_RECENTLY", "FRESH"}
            or drift_context.get("signals") == []
    ):
        return "PARTIAL_DATA"
    if str(actions.get("confidence")) == "HIGH":
        return "SUFFICIENT_DATA"
    return "STABLE_BASELINE"


def create_advisory_repository(config: AdvisoryPersistenceConfig) -> AdvisoryEventRepository:
    try:
        import pymongo  # type: ignore
    except ImportError:
        return UnavailableAdvisoryEventRepository()
    return MongoAdvisoryEventRepository(
        mongodb_uri=config.mongodb_uri,
        collection_name=config.collection,
        retention_limit=config.retention_limit,
        client=pymongo.MongoClient(config.mongodb_uri, serverSelectionTimeoutMS=250),
    )


def _bounded_lifecycle_context(lifecycle_context: dict[str, Any]) -> dict[str, Any]:
    return {
        "current_model_version": lifecycle_context.get("current_model_version"),
        "model_loaded_at": lifecycle_context.get("model_loaded_at"),
        "model_changed_recently": bool(lifecycle_context.get("model_changed_recently")),
        "recent_lifecycle_event_count": int(lifecycle_context.get("recent_lifecycle_event_count") or 0),
    }


def _recommended_actions(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value[:MAX_OPERATOR_ACTIONS]]


def _bounded_explanation(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    return value[:240]


def _matches_recent(event: dict[str, Any], fingerprint: tuple[str, str, str, str], now: datetime) -> bool:
    event_fingerprint = (
        str(event.get("model_name")),
        str(event.get("model_version")),
        str(event.get("severity")),
        str(event.get("drift_status")),
    )
    if event_fingerprint != fingerprint:
        return False
    created_at = _parse_time(event.get("created_at"))
    return now - created_at <= ADVISORY_DEDUP_WINDOW


def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        return datetime.fromtimestamp(0, timezone.utc)
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return datetime.fromtimestamp(0, timezone.utc)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _valid_model_version_filter(value: str | None) -> bool:
    if not isinstance(value, str) or not value or len(value) > 80:
        return False
    return all(character.isalnum() or character in {".", "_", "-"} for character in value)
