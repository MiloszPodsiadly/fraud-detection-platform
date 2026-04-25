from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlparse

from app.governance.drift import evaluate_drift
from app.governance.profile import inference_profile_summary, reference_profile_summary


DEFAULT_MONGODB_URI = "mongodb://mongodb:27017/fraud_governance"
DEFAULT_COLLECTION = "ml_governance_snapshots"
DEFAULT_RETENTION_LIMIT = 500
DEFAULT_SNAPSHOT_INTERVAL_REQUESTS = 50
MAX_HISTORY_LIMIT = 100
MAX_PERSISTED_SIGNALS = 16


@dataclass(frozen=True)
class GovernancePersistenceConfig:
    mongodb_uri: str
    collection: str
    retention_limit: int
    snapshot_interval_requests: int

    @staticmethod
    def from_env() -> GovernancePersistenceConfig:
        return GovernancePersistenceConfig(
            mongodb_uri=os.getenv("MONGODB_URI", DEFAULT_MONGODB_URI),
            collection=os.getenv("GOVERNANCE_SNAPSHOT_COLLECTION", DEFAULT_COLLECTION),
            retention_limit=_positive_int(
                os.getenv("GOVERNANCE_SNAPSHOT_RETENTION_LIMIT"),
                DEFAULT_RETENTION_LIMIT,
            ),
            snapshot_interval_requests=_positive_int(
                os.getenv("GOVERNANCE_SNAPSHOT_INTERVAL_REQUESTS"),
                DEFAULT_SNAPSHOT_INTERVAL_REQUESTS,
            ),
        )


class GovernanceSnapshotRepository:
    """Storage seam for aggregate governance snapshots."""

    def persist(self, document: dict[str, Any]) -> None:
        raise NotImplementedError

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise NotImplementedError

    def available(self) -> bool:
        raise NotImplementedError


class UnavailableGovernanceSnapshotRepository(GovernanceSnapshotRepository):
    def persist(self, document: dict[str, Any]) -> None:
        raise RuntimeError("governance snapshot persistence unavailable")

    def history(self, limit: int) -> list[dict[str, Any]]:
        raise RuntimeError("governance snapshot persistence unavailable")

    def available(self) -> bool:
        return False


class MongoGovernanceSnapshotRepository(GovernanceSnapshotRepository):
    """Optional MongoDB-backed snapshot repository with best-effort retention."""

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
        cursor = collection.find({}, {"_id": 0}).sort("createdAt", -1).limit(limit)
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
        collection.create_index([("createdAt", -1)], background=True)
        collection.create_index([("modelName", 1), ("modelVersion", 1), ("createdAt", -1)], background=True)

    def _enforce_retention(self, model_name: str, model_version: str) -> None:
        if self.retention_limit <= 0:
            return
        collection = self._get_collection()
        query = {"modelName": model_name, "modelVersion": model_version}
        excess = collection.count_documents(query) - self.retention_limit
        if excess <= 0:
            return
        stale = collection.find(query, {"_id": 1}).sort("createdAt", 1).limit(excess)
        stale_ids = [document["_id"] for document in stale if "_id" in document]
        if stale_ids:
            collection.delete_many({"_id": {"$in": stale_ids}})


class GovernanceSnapshotService:
    """Coordinates bounded persistence without making scoring depend on storage."""

    def __init__(
            self,
            repository: GovernanceSnapshotRepository,
            config: GovernancePersistenceConfig,
    ) -> None:
        self.repository = repository
        self.config = config
        self._successful_scoring_requests = 0

    def should_persist_after_success(self) -> bool:
        self._successful_scoring_requests += 1
        return self._successful_scoring_requests % self.config.snapshot_interval_requests == 0

    def build_document(
            self,
            model: dict[str, Any],
            reference_profile: dict[str, Any],
            inference_profile: dict[str, Any],
            drift: dict[str, Any],
    ) -> dict[str, Any]:
        created_at = datetime.now(timezone.utc)
        reference = reference_profile_summary(reference_profile)
        inference = inference_profile_summary(inference_profile)
        return {
            "snapshotId": str(uuid.uuid4()),
            "createdAt": created_at,
            "modelName": model.get("model_name"),
            "modelVersion": model.get("model_version"),
            "referenceProfileId": reference.get("profileVersion"),
            "referenceProfile": reference,
            "referenceQuality": reference.get("reference_quality"),
            "inferenceProfileSummary": inference,
            "driftStatus": drift.get("status"),
            "driftConfidence": drift.get("confidence"),
            "driftSignalsSummary": _drift_signals_summary(drift.get("signals")),
            "observationCount": int(inference.get("observation_count") or 0),
        }

    def persist_snapshot(
            self,
            model: dict[str, Any],
            reference_profile: dict[str, Any],
            inference_profile: dict[str, Any],
            drift: dict[str, Any],
    ) -> dict[str, Any]:
        document = self.build_document(model, reference_profile, inference_profile, drift)
        self.repository.persist(document)
        return document

    def history_response(self, limit: int, fallback_snapshot: dict[str, Any]) -> dict[str, Any]:
        bounded_limit = max(min(limit, MAX_HISTORY_LIMIT), 1)
        try:
            snapshots = self.repository.history(bounded_limit)
        except Exception:
            return {
                "status": "UNAVAILABLE",
                "count": 1,
                "oldestTimestamp": fallback_snapshot.get("createdAt"),
                "newestTimestamp": fallback_snapshot.get("createdAt"),
                "snapshots": [fallback_snapshot],
            }
        return {
            "status": "AVAILABLE",
            "count": len(snapshots),
            "oldestTimestamp": snapshots[-1]["createdAt"] if snapshots else None,
            "newestTimestamp": snapshots[0]["createdAt"] if snapshots else None,
            "snapshots": snapshots,
        }


def create_snapshot_repository(config: GovernancePersistenceConfig) -> GovernanceSnapshotRepository:
    try:
        import pymongo  # type: ignore
    except ImportError:
        return UnavailableGovernanceSnapshotRepository()
    return MongoGovernanceSnapshotRepository(
        mongodb_uri=config.mongodb_uri,
        collection_name=config.collection,
        retention_limit=config.retention_limit,
        client=pymongo.MongoClient(config.mongodb_uri, serverSelectionTimeoutMS=250),
    )


def current_snapshot_document(
        service: GovernanceSnapshotService,
        model: dict[str, Any],
        reference_profile: dict[str, Any],
        inference_profile: dict[str, Any],
) -> dict[str, Any]:
    drift = evaluate_drift(reference_profile, inference_profile)
    document = service.build_document(model, reference_profile, inference_profile, drift)
    return _json_safe(document)


def _drift_signals_summary(signals: Any) -> dict[str, Any]:
    severity_counts = {"OK": 0, "WATCH": 0, "DRIFT": 0}
    strongest: list[dict[str, Any]] = []
    if isinstance(signals, list):
        for signal in signals:
            if not isinstance(signal, dict):
                continue
            severity = str(signal.get("severity", "OK"))
            if severity in severity_counts:
                severity_counts[severity] += 1
            if len(strongest) < MAX_PERSISTED_SIGNALS and severity in {"WATCH", "DRIFT"}:
                strongest.append({
                    "drift_type": signal.get("drift_type"),
                    "name": signal.get("name"),
                    "statistic": signal.get("statistic"),
                    "severity": severity,
                    "absolute_difference": signal.get("absolute_difference"),
                    "z_score": signal.get("z_score"),
                })
    return {
        "signal_count": sum(severity_counts.values()),
        "severity_counts": severity_counts,
        "strongest_signals": strongest,
    }


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
