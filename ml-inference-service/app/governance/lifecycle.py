from __future__ import annotations

import hashlib
import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock
from typing import Any

from app.governance.persistence import _database_name, _json_safe, _new_mongo_client, _positive_int


DEFAULT_LIFECYCLE_COLLECTION = "ml_model_lifecycle_events"
DEFAULT_LIFECYCLE_RETENTION_LIMIT = 200
MAX_LIFECYCLE_HISTORY_LIMIT = 100
LIFECYCLE_MODE = "READ_ONLY"
RECENT_LIFECYCLE_WINDOW = timedelta(hours=24)


class LifecycleEventRepository:
    """Storage seam for read-only model lifecycle events."""

    def persist(self, event: dict[str, Any]) -> None:
        raise NotImplementedError

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise NotImplementedError

    def available(self) -> bool:
        raise NotImplementedError


class UnavailableLifecycleEventRepository(LifecycleEventRepository):
    def persist(self, event: dict[str, Any]) -> None:
        raise RuntimeError("model lifecycle persistence unavailable")

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise RuntimeError("model lifecycle persistence unavailable")

    def available(self) -> bool:
        return False


class MongoLifecycleEventRepository(LifecycleEventRepository):
    """Optional MongoDB-backed lifecycle event repository with bounded retention."""

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
        document = _storage_document(event)
        collection = self._get_collection()
        collection.insert_one(document)
        self._enforce_retention(
            model_name=str(document.get("modelName")),
            model_version=str(document.get("modelVersion")),
        )

    def history(self, limit: int) -> list[dict[str, Any]]:
        collection = self._get_collection()
        cursor = collection.find({}, {"_id": 0}).sort("occurredAt", -1).limit(limit)
        return [_api_event(_json_safe(document)) for document in cursor]

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
        collection.create_index([("occurredAt", -1)], background=True)
        collection.create_index([("modelName", 1), ("modelVersion", 1), ("occurredAt", -1)], background=True)

    def _enforce_retention(self, model_name: str, model_version: str) -> None:
        if self.retention_limit <= 0:
            return
        collection = self._get_collection()
        query = {"modelName": model_name, "modelVersion": model_version}
        excess = collection.count_documents(query) - self.retention_limit
        if excess <= 0:
            return
        stale = collection.find(query, {"_id": 1}).sort("occurredAt", 1).limit(excess)
        stale_ids = [document["_id"] for document in stale if "_id" in document]
        if stale_ids:
            collection.delete_many({"_id": {"$in": stale_ids}})


class LifecyclePersistenceConfig:
    def __init__(self, mongodb_uri: str, collection: str, retention_limit: int) -> None:
        self.mongodb_uri = mongodb_uri
        self.collection = collection
        self.retention_limit = retention_limit

    @staticmethod
    def from_env() -> LifecyclePersistenceConfig:
        return LifecyclePersistenceConfig(
            mongodb_uri=os.getenv("MONGODB_URI", "mongodb://mongodb:27017/fraud_governance"),
            collection=os.getenv("MODEL_LIFECYCLE_COLLECTION", DEFAULT_LIFECYCLE_COLLECTION),
            retention_limit=_positive_int(
                os.getenv("MODEL_LIFECYCLE_RETENTION_LIMIT"),
                DEFAULT_LIFECYCLE_RETENTION_LIMIT,
            ),
        )


class ModelLifecycleService:
    """Coordinates lifecycle history without making inference depend on storage."""

    def __init__(
            self,
            repository: LifecycleEventRepository,
            config: LifecyclePersistenceConfig,
    ) -> None:
        self.repository = repository
        self.config = config
        self._events: list[dict[str, Any]] = []
        self._lock = Lock()

    def record_event(
            self,
            event_type: str,
            model: dict[str, Any],
            source: str,
            reason: str,
            metadata_summary: dict[str, Any] | None = None,
            previous_model_version: str | None = None,
    ) -> tuple[dict[str, Any], str]:
        event = {
            "event_id": str(uuid.uuid4()),
            "event_type": event_type,
            "occurred_at": _utc_now(),
            "model_name": model.get("model_name"),
            "model_version": model.get("model_version"),
            "previous_model_version": previous_model_version,
            "source": source,
            "reason": reason,
            "metadata_summary": _bounded_summary(metadata_summary or {}),
        }
        self._append_memory(event)
        try:
            self.repository.persist(event)
            return event, "persisted"
        except Exception:
            return event, "memory"

    def record_history_status(self, model: dict[str, Any]) -> tuple[dict[str, Any], str]:
        available = self.repository.available()
        event_type = "GOVERNANCE_HISTORY_AVAILABLE" if available else "GOVERNANCE_HISTORY_UNAVAILABLE"
        return self.record_event(
            event_type,
            model,
            source="mongo_lifecycle_repository",
            reason="lifecycle event history availability checked",
            metadata_summary={"status": "AVAILABLE" if available else "UNAVAILABLE"},
        )

    def history_response(self, current_model: dict[str, Any], limit: int | None = None) -> dict[str, Any]:
        bounded_limit = self._bounded_limit(limit)
        try:
            events = self.repository.history(bounded_limit)
            status = "AVAILABLE"
        except Exception:
            events = self.memory_history(bounded_limit)
            status = "PARTIAL" if events else "UNAVAILABLE"
        return {
            "status": status,
            "current_model": current_model,
            "count": len(events),
            "retention_limit": self.config.retention_limit,
            "lifecycle_events": events,
        }

    def memory_history(self, limit: int | None = None) -> list[dict[str, Any]]:
        bounded_limit = self._bounded_limit(limit)
        with self._lock:
            return [dict(event) for event in reversed(self._events[-bounded_limit:])]

    def lifecycle_context(self, current_model: dict[str, Any]) -> dict[str, Any]:
        recent_events = self._recent_events()
        current_version = current_model.get("model_version")
        changed_recently = any(
            event.get("previous_model_version") not in {None, current_version}
            for event in recent_events
        )
        return {
            "current_model_version": current_version,
            "model_loaded_at": current_model.get("loaded_at"),
            "model_changed_recently": changed_recently,
            "recent_lifecycle_event_count": len(recent_events),
        }

    def _append_memory(self, event: dict[str, Any]) -> None:
        with self._lock:
            self._events.append(dict(event))
            if len(self._events) > self.config.retention_limit:
                self._events = self._events[-self.config.retention_limit:]

    def _recent_events(self) -> list[dict[str, Any]]:
        cutoff = datetime.now(timezone.utc) - RECENT_LIFECYCLE_WINDOW
        with self._lock:
            return [
                dict(event)
                for event in self._events
                if _parse_time(event.get("occurred_at")) >= cutoff
            ]

    def _bounded_limit(self, limit: int | None) -> int:
        requested = self.config.retention_limit if limit is None else limit
        return max(min(requested, MAX_LIFECYCLE_HISTORY_LIMIT, self.config.retention_limit), 1)


def create_lifecycle_repository(config: LifecyclePersistenceConfig) -> LifecycleEventRepository:
    try:
        import pymongo  # type: ignore
    except ImportError:
        return UnavailableLifecycleEventRepository()
    return MongoLifecycleEventRepository(
        mongodb_uri=config.mongodb_uri,
        collection_name=config.collection,
        retention_limit=config.retention_limit,
        client=pymongo.MongoClient(config.mongodb_uri, serverSelectionTimeoutMS=250),
    )


def current_model_lifecycle_metadata(
        model: dict[str, Any],
        artifact_path: Path,
        reference_profile: dict[str, Any],
        loaded_at: datetime,
) -> dict[str, Any]:
    artifact = _load_artifact(artifact_path)
    training = artifact.get("training") if isinstance(artifact.get("training"), dict) else {}
    return {
        "model_name": model.get("model_name"),
        "model_version": model.get("model_version"),
        "model_family": model.get("model_family"),
        "loaded_at": loaded_at.isoformat(timespec="seconds"),
        "artifact_path_or_id": _safe_artifact_id(artifact_path),
        "artifact_source": "LOCAL_FILE" if artifact_path.exists() else "UNKNOWN",
        "artifact_checksum": _artifact_checksum(artifact_path),
        "feature_set_version": artifact.get("featureSetVersion") or training.get("featureSetVersion"),
        "training_mode": model.get("training_mode") or artifact.get("trainingMode") or training.get("trainingMode"),
        "reference_profile_id": reference_profile.get("profileVersion"),
        "runtime_environment": "ml-inference-service",
        "lifecycle_mode": LIFECYCLE_MODE,
    }


def lifecycle_metadata_summary(current_model: dict[str, Any]) -> dict[str, Any]:
    return {
        "artifact_source": current_model.get("artifact_source"),
        "artifact_checksum": current_model.get("artifact_checksum"),
        "feature_set_version": current_model.get("feature_set_version"),
        "training_mode": current_model.get("training_mode"),
        "reference_profile_id": current_model.get("reference_profile_id"),
        "lifecycle_mode": current_model.get("lifecycle_mode"),
    }


def _storage_document(event: dict[str, Any]) -> dict[str, Any]:
    return {
        "eventId": event.get("event_id"),
        "eventType": event.get("event_type"),
        "occurredAt": event.get("occurred_at"),
        "modelName": event.get("model_name"),
        "modelVersion": event.get("model_version"),
        "previousModelVersion": event.get("previous_model_version"),
        "source": event.get("source"),
        "reason": event.get("reason"),
        "metadataSummary": event.get("metadata_summary"),
    }


def _api_event(document: dict[str, Any]) -> dict[str, Any]:
    if "event_id" in document:
        return document
    return {
        "event_id": document.get("eventId"),
        "event_type": document.get("eventType"),
        "occurred_at": document.get("occurredAt"),
        "model_name": document.get("modelName"),
        "model_version": document.get("modelVersion"),
        "previous_model_version": document.get("previousModelVersion"),
        "source": document.get("source"),
        "reason": document.get("reason"),
        "metadata_summary": document.get("metadataSummary") or {},
    }


def _bounded_summary(summary: dict[str, Any]) -> dict[str, Any]:
    allowed = {
        "artifact_source",
        "artifact_checksum",
        "feature_set_version",
        "training_mode",
        "reference_profile_id",
        "lifecycle_mode",
        "status",
        "feature_count",
    }
    return {key: value for key, value in summary.items() if key in allowed}


def _safe_artifact_id(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(Path(__file__).resolve().parents[1]))
    except ValueError:
        return path.name
    except OSError:
        return path.name


def _artifact_checksum(path: Path) -> str | None:
    try:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None
    return f"sha256:{digest}"


def _load_artifact(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def _parse_time(value: Any) -> datetime:
    if not isinstance(value, str):
        return datetime.fromtimestamp(0, timezone.utc)
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return datetime.fromtimestamp(0, timezone.utc)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
