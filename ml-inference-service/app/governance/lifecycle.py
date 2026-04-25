from __future__ import annotations

import hashlib
import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


DEFAULT_LIFECYCLE_COLLECTION = "ml_model_lifecycle_events"
DEFAULT_LIFECYCLE_RETENTION_LIMIT = 200
MAX_LIFECYCLE_HISTORY_LIMIT = 100
LIFECYCLE_MODE = "READ_ONLY"
EVENT_TYPES = (
    "MODEL_LOADED",
    "MODEL_METADATA_DETECTED",
    "REFERENCE_PROFILE_LOADED",
    "GOVERNANCE_HISTORY_AVAILABLE",
    "GOVERNANCE_HISTORY_UNAVAILABLE",
)


@dataclass(frozen=True)
class ModelLifecycleConfig:
    mongodb_uri: str
    collection: str
    retention_limit: int

    @staticmethod
    def from_env(default_mongodb_uri: str) -> ModelLifecycleConfig:
        return ModelLifecycleConfig(
            mongodb_uri=os.getenv("MONGODB_URI", default_mongodb_uri),
            collection=os.getenv("MODEL_LIFECYCLE_COLLECTION", DEFAULT_LIFECYCLE_COLLECTION),
            retention_limit=_positive_int(
                os.getenv("MODEL_LIFECYCLE_RETENTION_LIMIT"),
                DEFAULT_LIFECYCLE_RETENTION_LIMIT,
            ),
        )


class LifecycleEventRepository:
    """Storage seam for read-only model lifecycle events."""

    def persist(self, document: dict[str, Any]) -> None:
        raise NotImplementedError

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise NotImplementedError

    def available(self) -> bool:
        raise NotImplementedError


class UnavailableLifecycleEventRepository(LifecycleEventRepository):
    def persist(self, document: dict[str, Any]) -> None:
        raise RuntimeError("model lifecycle persistence unavailable")

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise RuntimeError("model lifecycle persistence unavailable")

    def available(self) -> bool:
        return False


class MongoLifecycleEventRepository(LifecycleEventRepository):
    """Optional MongoDB-backed lifecycle repository with best-effort retention."""

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

    def persist(self, document: dict[str, Any]) -> None:
        collection = self._get_collection()
        collection.insert_one(dict(document))
        self._enforce_retention(
            model_name=str(document.get("modelName")),
            model_version=str(document.get("modelVersion")),
        )

    def history(self, limit: int) -> list[dict[str, Any]]:
        collection = self._get_collection()
        cursor = collection.find({}, {"_id": 0}).sort("occurredAt", -1).limit(limit)
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


class ModelLifecycleService:
    """Coordinates bounded lifecycle visibility without lifecycle control."""

    def __init__(
            self,
            repository: LifecycleEventRepository,
            config: ModelLifecycleConfig,
    ) -> None:
        self.repository = repository
        self.config = config
        self._events: list[dict[str, Any]] = []

    def record_event(
            self,
            event_type: str,
            model_lifecycle: dict[str, Any],
            reason: str,
            metadata_summary: dict[str, Any] | None = None,
            source: str = "ml-inference-service",
            previous_model_version: str | None = None,
    ) -> bool:
        event = self.build_event(
            event_type=event_type,
            model_lifecycle=model_lifecycle,
            source=source,
            reason=reason,
            metadata_summary=metadata_summary or {},
            previous_model_version=previous_model_version,
        )
        self._remember(event)
        try:
            self.repository.persist(event)
            return True
        except Exception:
            return False

    def build_event(
            self,
            event_type: str,
            model_lifecycle: dict[str, Any],
            source: str,
            reason: str,
            metadata_summary: dict[str, Any],
            previous_model_version: str | None = None,
    ) -> dict[str, Any]:
        safe_type = event_type if event_type in EVENT_TYPES else "MODEL_METADATA_DETECTED"
        return {
            "eventId": str(uuid.uuid4()),
            "eventType": safe_type,
            "occurredAt": datetime.now(timezone.utc),
            "modelName": model_lifecycle.get("model_name"),
            "modelVersion": model_lifecycle.get("model_version"),
            "previousModelVersion": previous_model_version,
            "source": source,
            "reason": _bounded_text(reason),
            "metadataSummary": _metadata_summary(metadata_summary),
        }

    def lifecycle_response(self, limit: int, model_lifecycle: dict[str, Any]) -> dict[str, Any]:
        bounded_limit = max(min(limit, MAX_LIFECYCLE_HISTORY_LIMIT), 1)
        try:
            events = self.repository.history(bounded_limit)
            status = "AVAILABLE"
        except Exception:
            events = [_json_safe(event) for event in self._events[:bounded_limit]]
            status = "PARTIAL" if events else "UNAVAILABLE"
        return {
            "status": status,
            "count": len(events),
            "model_lifecycle": model_lifecycle,
            "events": events,
        }

    def drift_action_context(self, model_lifecycle: dict[str, Any]) -> dict[str, Any]:
        loaded_at = str(model_lifecycle.get("loaded_at") or "")
        return {
            "current_model_version": model_lifecycle.get("model_version"),
            "model_loaded_at": loaded_at,
            "model_loaded_recently": _loaded_recently(loaded_at),
            "recent_lifecycle_event_count": min(len(self._events), MAX_LIFECYCLE_HISTORY_LIMIT),
        }

    def _remember(self, event: dict[str, Any]) -> None:
        self._events.insert(0, _json_safe(event))
        self._events = self._events[:max(self.config.retention_limit, 1)]


def model_lifecycle_metadata(
        model: dict[str, Any],
        reference_profile: dict[str, Any],
        artifact_path: Path,
        loaded_at: datetime,
) -> dict[str, Any]:
    artifact = model.get("artifact") if isinstance(model.get("artifact"), dict) else {}
    return {
        "model_name": model.get("model_name"),
        "model_version": model.get("model_version"),
        "model_family": model.get("model_family"),
        "loaded_at": loaded_at.isoformat(timespec="seconds"),
        "artifact_path_or_id": artifact_path.name,
        "artifact_source": artifact.get("training_source") or "local_artifact",
        "artifact_checksum": _artifact_checksum(artifact_path),
        "feature_set_version": None,
        "training_mode": model.get("training_mode") or "unknown",
        "reference_profile_id": reference_profile.get("profileVersion"),
        "runtime_environment": {
            "service": "ml-inference-service",
            "runtime": "python",
        },
        "lifecycle_mode": LIFECYCLE_MODE,
    }


def create_lifecycle_repository(config: ModelLifecycleConfig) -> LifecycleEventRepository:
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


def _artifact_checksum(path: Path) -> str | None:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def _loaded_recently(loaded_at: str) -> bool:
    try:
        parsed = datetime.fromisoformat(loaded_at)
    except ValueError:
        return False
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return (datetime.now(timezone.utc) - parsed).total_seconds() <= 3600


def _metadata_summary(metadata: dict[str, Any]) -> dict[str, Any]:
    allowed = {
        "artifact_source",
        "lifecycle_mode",
        "reference_profile_id",
        "reference_quality",
        "history_status",
    }
    return {
        str(key): _bounded_text(value)
        for key, value in metadata.items()
        if key in allowed and value is not None
    }


def _bounded_text(value: Any) -> str:
    return str(value)[:120]


def _json_safe(value: Any) -> Any:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items() if key != "_id"}
    if isinstance(value, list):
        return [_json_safe(item) for item in value]
    return value


def _new_mongo_client(mongodb_uri: str) -> Any:
    import pymongo  # type: ignore

    return pymongo.MongoClient(mongodb_uri, serverSelectionTimeoutMS=250)


def _database_name(mongodb_uri: str) -> str:
    path = urlparse(mongodb_uri).path.strip("/")
    return path or "fraud_governance"


def _positive_int(raw: str | None, default: int) -> int:
    try:
        value = int(raw or default)
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default
