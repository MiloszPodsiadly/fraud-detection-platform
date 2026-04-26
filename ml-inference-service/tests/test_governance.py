import json
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

from app import server
from app.governance.advisory import (
    AdvisoryEventRepository,
    AdvisoryEventService,
    AdvisoryPersistenceConfig,
    MAX_ADVISORY_LIMIT,
    MongoAdvisoryEventRepository,
    advisory_confidence_context,
    build_advisory_event,
    should_emit_advisory,
)
from app.governance.actions import recommend_drift_actions, with_model_lifecycle_context
from app.governance.drift import CONFIDENCE_ORDER, MIN_OBSERVATIONS, evaluate_drift
from app.governance.lifecycle import (
    LifecycleEventRepository,
    LifecyclePersistenceConfig,
    MAX_LIFECYCLE_HISTORY_LIMIT,
    ModelLifecycleService,
    MongoLifecycleEventRepository,
    current_model_lifecycle_metadata,
)
from app.governance.persistence import (
    GovernancePersistenceConfig,
    GovernanceSnapshotRepository,
    GovernanceSnapshotService,
    MAX_HISTORY_LIMIT,
    MongoGovernanceSnapshotRepository,
    UnavailableGovernanceSnapshotRepository,
)
from app.governance.profile import InferenceProfile, load_reference_profile


SENSITIVE_FIELDS = (
    "transactionId",
    "customerId",
    "accountId",
    "cardId",
    "userId",
    "correlationId",
    "txn-sensitive",
    "raw inference request",
    "raw feature row",
    "full payload",
)
SNAPSHOT_DIR = Path(__file__).parent / "snapshots"


class FakeSnapshotRepository(GovernanceSnapshotRepository):
    def __init__(self, fail: bool = False, documents: list[dict] | None = None):
        self.fail = fail
        self.documents = documents or []
        self.persisted = []

    def persist(self, document: dict) -> None:
        if self.fail:
            raise RuntimeError("fake persistence failure")
        self.persisted.append(document)

    def history(self, limit: int) -> list[dict]:
        if self.fail:
            raise RuntimeError("fake history failure")
        return self.documents[:limit]

    def available(self) -> bool:
        return not self.fail


class FakeLifecycleRepository(LifecycleEventRepository):
    def __init__(self, fail: bool = False, documents: list[dict] | None = None):
        self.fail = fail
        self.documents = documents or []
        self.persisted = []

    def persist(self, event: dict) -> None:
        if self.fail:
            raise RuntimeError("fake lifecycle persistence failure")
        self.persisted.append(event)

    def history(self, limit: int) -> list[dict]:
        if self.fail:
            raise RuntimeError("fake lifecycle history failure")
        return self.documents[:limit] or self.persisted[:limit]

    def available(self) -> bool:
        return not self.fail


class FakeAdvisoryRepository(AdvisoryEventRepository):
    def __init__(self, fail: bool = False, documents: list[dict] | None = None):
        self.fail = fail
        self.documents = documents or []
        self.persisted = []

    def persist(self, event: dict) -> None:
        if self.fail:
            raise RuntimeError("fake advisory persistence failure")
        self.persisted.append(event)

    def history(self, limit: int) -> list[dict]:
        if self.fail:
            raise RuntimeError("fake advisory history failure")
        return self.documents[:limit] or self.persisted[:limit]

    def available(self) -> bool:
        return not self.fail


class FakeCursor:
    def __init__(self, documents: list[dict]):
        self.documents = list(documents)

    def sort(self, field: str, direction: int):
        reverse = direction == -1
        self.documents.sort(key=lambda item: item.get(field), reverse=reverse)
        return self

    def limit(self, limit: int):
        self.documents = self.documents[:limit]
        return self

    def __iter__(self):
        return iter(self.documents)


class FakeCollection:
    def __init__(self):
        self.documents = []
        self.indexes = []
        self.next_id = 1

    def create_index(self, spec, background=True):
        self.indexes.append((tuple(spec), background))

    def insert_one(self, document: dict):
        stored = dict(document)
        stored["_id"] = self.next_id
        self.next_id += 1
        self.documents.append(stored)

    def count_documents(self, query: dict):
        return len([document for document in self.documents if self._matches(document, query)])

    def find(self, query: dict, projection: dict | None = None):
        documents = [document for document in self.documents if self._matches(document, query)]
        if projection == {"_id": 1}:
            documents = [
                {
                    "_id": document["_id"],
                    "createdAt": document.get("createdAt"),
                    "occurredAt": document.get("occurredAt"),
                    "created_at": document.get("created_at"),
                }
                for document in documents
            ]
        elif projection == {"_id": 0}:
            documents = [{key: value for key, value in document.items() if key != "_id"} for document in documents]
        return FakeCursor(documents)

    def delete_many(self, query: dict):
        ids = set(query.get("_id", {}).get("$in", []))
        self.documents = [document for document in self.documents if document.get("_id") not in ids]

    def _matches(self, document: dict, query: dict):
        return all(document.get(key) == value for key, value in query.items())


class FakeDatabase:
    def __init__(self, collection: FakeCollection):
        self.collection = collection

    def __getitem__(self, name: str):
        return self.collection


class FakeMongoClient:
    def __init__(self, collection: FakeCollection):
        self.database = FakeDatabase(collection)

    def get_default_database(self):
        return self.database


class MlGovernanceUnitTest(unittest.TestCase):
    def test_reference_profile_loads_successfully(self):
        profile = load_reference_profile()

        self.assertTrue(profile["available"])
        self.assertEqual(profile["status"], "loaded")
        self.assertEqual(profile["profileType"], "synthetic_local_reference")
        self.assertEqual(profile["source"], "synthetic")
        self.assertEqual(profile["reference_quality"], "SYNTHETIC")
        self.assertEqual(profile["sample_size"], 1000)
        self.assertIn("data_window", profile)
        self.assertIn("generated_by", profile)
        self.assertIn("numeric_feature_stats", profile)
        self.assertIn("score_distribution", profile)

    def test_missing_reference_profile_returns_unknown_safe_status(self):
        profile = load_reference_profile(Path("missing-reference-profile.json"))
        drift = evaluate_drift(profile, {"observation_count": MIN_OBSERVATIONS})

        self.assertFalse(profile["available"])
        self.assertEqual(profile["status"], "missing")
        self.assertEqual(drift["status"], "UNKNOWN")
        self.assertEqual(drift["reason"], "reference_profile_unavailable")
        self.assertEqual(drift["confidence"], "LOW")

    def test_drift_insufficient_data_returns_unknown(self):
        profile = load_reference_profile()
        inference = InferenceProfile(
            profile["model_name"],
            profile["model_version"],
            list(profile["numeric_feature_stats"]),
        )
        for _ in range(MIN_OBSERVATIONS - 1):
            inference.update({name: 0.0 for name in profile["numeric_feature_stats"]}, 0.01, "LOW")

        drift = evaluate_drift(profile, inference.snapshot())

        self.assertEqual(drift["status"], "UNKNOWN")
        self.assertEqual(drift["reason"], "insufficient_data")
        self.assertEqual(drift["confidence"], "LOW")
        self.assertEqual(drift["sample_size"], MIN_OBSERVATIONS - 1)

    def test_drift_status_changes_under_shifted_synthetic_input_with_enough_data(self):
        profile = load_reference_profile()
        inference = InferenceProfile(
            profile["model_name"],
            profile["model_version"],
            list(profile["numeric_feature_stats"]),
        )
        shifted = {name: 1.0 for name in profile["numeric_feature_stats"]}

        for _ in range(MIN_OBSERVATIONS):
            inference.update(shifted, 0.95, "CRITICAL")

        drift = evaluate_drift(profile, inference.snapshot())

        self.assertIn(drift["status"], {"WATCH", "DRIFT"})
        self.assertEqual(drift["confidence"], "LOW")
        self.assertEqual(drift["reason"], "non_production_reference_profile")
        self.assertTrue(any(signal["severity"] == "DRIFT" for signal in drift["signals"]))

    def test_confidence_increases_with_sample_size_for_production_reference(self):
        profile = dict(load_reference_profile())
        profile["reference_quality"] = "PRODUCTION"
        profile["source"] = "evaluation"
        confidences = []
        for sample_size in (MIN_OBSERVATIONS, MIN_OBSERVATIONS * 2, MIN_OBSERVATIONS * 5):
            inference = InferenceProfile(
                profile["model_name"],
                profile["model_version"],
                list(profile["numeric_feature_stats"]),
            )
            for index in range(sample_size):
                value = 0.2 if index % 2 == 0 else 0.8
                inference.update({name: value for name in profile["numeric_feature_stats"]}, value, "LOW")
            confidences.append(evaluate_drift(profile, inference.snapshot())["confidence"])

        self.assertEqual(confidences, ["LOW", "MEDIUM", "HIGH"])
        self.assertLess(CONFIDENCE_ORDER[confidences[0]], CONFIDENCE_ORDER[confidences[1]])
        self.assertLess(CONFIDENCE_ORDER[confidences[1]], CONFIDENCE_ORDER[confidences[2]])


class MlGovernancePersistenceTest(unittest.TestCase):
    def config(self, interval: int = 2, retention: int = 2):
        return GovernancePersistenceConfig(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection="ml_governance_snapshots",
            retention_limit=retention,
            snapshot_interval_requests=interval,
        )

    def snapshot_inputs(self):
        reference = load_reference_profile()
        inference = InferenceProfile(
            reference["model_name"],
            reference["model_version"],
            list(reference["numeric_feature_stats"]),
        )
        for _ in range(MIN_OBSERVATIONS):
            inference.update({name: 0.2 for name in reference["numeric_feature_stats"]}, 0.02, "LOW")
        inference_snapshot = inference.snapshot()
        drift = evaluate_drift(reference, inference_snapshot)
        model = {
            "model_name": reference["model_name"],
            "model_version": reference["model_version"],
            "model_family": "LOGISTIC_REGRESSION",
        }
        return model, reference, inference_snapshot, drift

    def test_snapshot_document_excludes_identifiers_and_raw_payload(self):
        service = GovernanceSnapshotService(FakeSnapshotRepository(), self.config())
        document = service.build_document(*self.snapshot_inputs())

        serialized = json.dumps(document, default=str)
        self.assertIn("snapshotId", document)
        self.assertIn("driftSignalsSummary", document)
        self.assertNotIn("rawFeatures", serialized)
        self.assertNotIn("features", serialized)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_snapshot_write_trigger_runs_every_configured_success_count(self):
        service = GovernanceSnapshotService(FakeSnapshotRepository(), self.config(interval=3))

        decisions = [service.should_persist_after_success() for _ in range(6)]

        self.assertEqual(decisions, [False, False, True, False, False, True])

    def test_history_response_is_bounded_by_limit(self):
        documents = [
            {"snapshotId": f"snapshot-{index}", "createdAt": f"2026-04-25T00:{index:02d}:00+00:00"}
            for index in range(150)
        ]
        service = GovernanceSnapshotService(FakeSnapshotRepository(documents=documents), self.config())

        response = service.history_response(999, {"snapshotId": "fallback", "createdAt": "now"})

        self.assertEqual(response["status"], "AVAILABLE")
        self.assertEqual(response["count"], MAX_HISTORY_LIMIT)
        self.assertEqual(len(response["snapshots"]), MAX_HISTORY_LIMIT)

    def test_unavailable_mongo_returns_safe_history_response(self):
        service = GovernanceSnapshotService(UnavailableGovernanceSnapshotRepository(), self.config())

        response = service.history_response(10, {"snapshotId": "current", "createdAt": "now"})

        self.assertEqual(response["status"], "UNAVAILABLE")
        self.assertEqual(response["count"], 1)
        self.assertEqual(response["snapshots"][0]["snapshotId"], "current")

    def test_retention_limit_is_enforced_per_model_version(self):
        collection = FakeCollection()
        repository = MongoGovernanceSnapshotRepository(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection_name="ml_governance_snapshots",
            retention_limit=2,
            client=FakeMongoClient(collection),
        )
        service = GovernanceSnapshotService(repository, self.config(retention=2))
        model, reference, inference, drift = self.snapshot_inputs()

        for _ in range(3):
            service.persist_snapshot(model, reference, inference, drift)

        self.assertEqual(collection.count_documents({"modelName": model["model_name"], "modelVersion": model["model_version"]}), 2)
        self.assertIn(((("createdAt", -1),), True), collection.indexes)

    def test_persistence_failure_does_not_fail_scoring_endpoint(self):
        original_service = server.SNAPSHOT_SERVICE
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.FraudInferenceHandler)
        port = httpd.server_address[1]
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            server.SNAPSHOT_SERVICE = GovernanceSnapshotService(FakeSnapshotRepository(fail=True), self.config(interval=1))
            connection = HTTPConnection("127.0.0.1", port, timeout=5)
            connection.request(
                "POST",
                "/v1/fraud/score",
                body=(
                    b'{"features":{"recentTransactionCount":1,"recentAmountSum":{"amount":45.0,"currency":"USD"},'
                    b'"transactionVelocityPerMinute":0.05,"merchantFrequency7d":1,"deviceNovelty":false,'
                    b'"countryMismatch":false,"proxyOrVpnDetected":false,"featureFlags":[]}}'
                ),
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            payload = response.read()
            status = response.status
            connection.close()
        finally:
            server.SNAPSHOT_SERVICE = original_service
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=5)

        self.assertEqual(status, 200)
        self.assertIn("fraudScore", json.loads(payload.decode("utf-8")))


class MlModelLifecycleUnitTest(unittest.TestCase):
    def config(self, retention: int = 3):
        return LifecyclePersistenceConfig(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection="ml_model_lifecycle_events",
            retention_limit=retention,
        )

    def model_metadata(self):
        reference = load_reference_profile()
        model = {
            "model_name": "python-logistic-fraud-model",
            "model_version": "lifecycle-test-v1",
            "model_family": "LOGISTIC_REGRESSION",
            "training_mode": "production",
        }
        return current_model_lifecycle_metadata(model, server.DEFAULT_ARTIFACT_PATH, reference, server.MODEL_LOADED_AT)

    def test_current_model_lifecycle_schema_is_stable_and_read_only(self):
        metadata = self.model_metadata()

        self.assertEqual(
            set(metadata),
            {
                "model_name",
                "model_version",
                "model_family",
                "loaded_at",
                "artifact_path_or_id",
                "artifact_source",
                "artifact_checksum",
                "feature_set_version",
                "training_mode",
                "reference_profile_id",
                "runtime_environment",
                "lifecycle_mode",
            },
        )
        self.assertEqual(metadata["lifecycle_mode"], "READ_ONLY")
        self.assertFalse(Path(str(metadata["artifact_path_or_id"])).is_absolute())
        self.assertTrue(str(metadata["artifact_checksum"]).startswith("sha256:"))

    def test_lifecycle_history_is_bounded_and_excludes_raw_artifacts(self):
        service = ModelLifecycleService(FakeLifecycleRepository(), self.config(retention=2))
        metadata = self.model_metadata()

        for index in range(4):
            service.record_event(
                "MODEL_METADATA_DETECTED",
                metadata,
                source="test",
                reason=f"event {index}",
                metadata_summary={
                    "artifact_checksum": metadata["artifact_checksum"],
                    "rawArtifact": server.DEFAULT_ARTIFACT_PATH.read_text(encoding="utf-8"),
                },
            )
        response = service.history_response(metadata)
        serialized = json.dumps(response)

        self.assertEqual(response["status"], "AVAILABLE")
        self.assertLessEqual(response["count"], 2)
        self.assertNotIn("rawArtifact", serialized)
        self.assertNotIn("weights", serialized)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_lifecycle_history_falls_back_to_memory_when_mongo_unavailable(self):
        service = ModelLifecycleService(FakeLifecycleRepository(fail=True), self.config(retention=3))
        metadata = self.model_metadata()

        event, status = service.record_event(
            "MODEL_LOADED",
            metadata,
            source="test",
            reason="loaded under test",
            metadata_summary={"lifecycle_mode": "READ_ONLY"},
        )
        response = service.history_response(metadata)

        self.assertEqual(status, "memory")
        self.assertEqual(response["status"], "PARTIAL")
        self.assertEqual(response["count"], 1)
        self.assertEqual(response["lifecycle_events"][0]["event_id"], event["event_id"])

    def test_mongo_lifecycle_repository_indexes_and_retention(self):
        collection = FakeCollection()
        repository = MongoLifecycleEventRepository(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection_name="ml_model_lifecycle_events",
            retention_limit=2,
            client=FakeMongoClient(collection),
        )
        service = ModelLifecycleService(repository, self.config(retention=2))
        metadata = self.model_metadata()

        for _ in range(3):
            service.record_event(
                "MODEL_LOADED",
                metadata,
                source="test",
                reason="loaded under test",
                metadata_summary={"lifecycle_mode": "READ_ONLY"},
            )

        self.assertEqual(collection.count_documents({"modelName": metadata["model_name"], "modelVersion": metadata["model_version"]}), 2)
        self.assertIn(((("occurredAt", -1),), True), collection.indexes)
        self.assertIn(((("modelName", 1), ("modelVersion", 1), ("occurredAt", -1)), True), collection.indexes)

    def test_lifecycle_persistence_failure_does_not_fail_scoring_endpoint(self):
        original_service = server.LIFECYCLE_SERVICE
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.FraudInferenceHandler)
        port = httpd.server_address[1]
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            server.LIFECYCLE_SERVICE = ModelLifecycleService(FakeLifecycleRepository(fail=True), self.config())
            connection = HTTPConnection("127.0.0.1", port, timeout=5)
            connection.request(
                "POST",
                "/v1/fraud/score",
                body=(
                    b'{"features":{"recentTransactionCount":1,"recentAmountSum":{"amount":45.0,"currency":"USD"},'
                    b'"transactionVelocityPerMinute":0.05,"merchantFrequency7d":1,"deviceNovelty":false,'
                    b'"countryMismatch":false,"proxyOrVpnDetected":false,"featureFlags":[]}}'
                ),
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            payload = response.read()
            status = response.status
            connection.close()
        finally:
            server.LIFECYCLE_SERVICE = original_service
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=5)

        self.assertEqual(status, 200)
        self.assertIn("fraudScore", json.loads(payload.decode("utf-8")))


class MlGovernanceAdvisoryTest(unittest.TestCase):
    def config(self, retention: int = 3):
        return AdvisoryPersistenceConfig(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection="ml_governance_advisory_events",
            retention_limit=retention,
        )

    def model(self):
        return {
            "model_name": "python-logistic-fraud-model",
            "model_version": "advisory-test-v1",
        }

    def actions(
            self,
            severity: str = "HIGH",
            confidence: str = "HIGH",
            escalation: str = "MODEL_OWNER_REVIEW",
    ):
        return {
            "severity": severity,
            "confidence": confidence,
            "drift_status": "DRIFT",
            "recommended_actions": [
                "ESCALATE_MODEL_REVIEW",
                "OPEN_MODEL_DATA_REVIEW",
                "KEEP_SCORING_UNCHANGED",
            ],
            "escalation": escalation,
            "explanation": "score p95 increased by 18% compared to reference profile",
        }

    def lifecycle_context(self):
        return {
            "current_model_version": "advisory-test-v1",
            "model_loaded_at": "2026-04-26T00:00:00+00:00",
            "model_changed_recently": False,
            "recent_lifecycle_event_count": 2,
            "artifact_checksum": "sha256:not-allowed-in-event-context",
            "artifact_path_or_id": "not-allowed",
        }

    def drift_context(
            self,
            sample_size: int = 500,
            min_observations: int = 100,
            confidence: str = "HIGH",
            reason: str | None = None,
            inference_profile_status: str = "STABLE",
            signals: list[dict] | None = None,
    ):
        return {
            "sample_size": sample_size,
            "observation_count": sample_size,
            "min_observations": min_observations,
            "confidence": confidence,
            "reason": reason,
            "inference_profile_status": inference_profile_status,
            "signals": signals if signals is not None else [{"severity": "DRIFT"}],
        }

    def test_emits_only_for_meaningful_escalated_advisories(self):
        self.assertTrue(should_emit_advisory(self.actions(severity="HIGH", confidence="HIGH", escalation="NONE")))
        self.assertTrue(should_emit_advisory(self.actions(severity="MEDIUM", confidence="MEDIUM", escalation="OPERATOR_REVIEW")))
        self.assertFalse(should_emit_advisory(self.actions(severity="LOW", confidence="HIGH", escalation="OPERATOR_REVIEW")))
        self.assertFalse(should_emit_advisory(self.actions(severity="INFO", confidence="HIGH", escalation="OPERATOR_REVIEW")))
        self.assertFalse(should_emit_advisory(self.actions(severity="HIGH", confidence="LOW", escalation="MODEL_OWNER_REVIEW")))
        self.assertFalse(should_emit_advisory(self.actions(severity="MEDIUM", confidence="MEDIUM", escalation="NONE")))

    def test_advisory_event_is_bounded_and_excludes_sensitive_data(self):
        event = build_advisory_event(
            {
                **self.actions(),
                "recommended_actions": ["A", "B", "C", "D", "E", "F"],
                "transactionId": "txn-sensitive",
                "explanation": "score p95 increased by 18% compared to reference profile",
            },
            self.model(),
            self.lifecycle_context(),
            self.drift_context(),
        )
        serialized = json.dumps(event)

        self.assertEqual(event["event_type"], "GOVERNANCE_DRIFT_ADVISORY")
        self.assertEqual(event["severity"], "HIGH")
        self.assertIn(event["advisory_confidence_context"], {"LOW_SAMPLE", "PARTIAL_DATA", "STABLE_BASELINE", "SUFFICIENT_DATA"})
        self.assertLessEqual(len(event["recommended_actions"]), 5)
        self.assertEqual(
            set(event["lifecycle_context"]),
            {
                "current_model_version",
                "model_loaded_at",
                "model_changed_recently",
                "recent_lifecycle_event_count",
            },
        )
        self.assertNotIn("artifact_checksum", serialized)
        self.assertNotIn("artifact_path_or_id", serialized)
        self.assertNotIn("raw feature", serialized.lower())
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_advisory_persistence_falls_back_to_memory(self):
        service = AdvisoryEventService(FakeAdvisoryRepository(fail=True), self.config())

        event, status = service.emit_if_needed(self.actions(), self.model(), self.lifecycle_context(), self.drift_context())
        response = service.history_response()

        self.assertEqual(status, "memory")
        self.assertIsNotNone(event)
        self.assertEqual(response["status"], "PARTIAL")
        self.assertEqual(response["count"], 1)
        self.assertEqual(response["advisory_events"][0]["event_id"], event["event_id"])

    def test_advisory_history_is_bounded(self):
        service = AdvisoryEventService(FakeAdvisoryRepository(), self.config(retention=2))

        for index in range(4):
            actions = {**self.actions(), "drift_status": f"DRIFT-{index}"}
            service.emit_if_needed(actions, self.model(), self.lifecycle_context(), self.drift_context())
        response = service.history_response(999)

        self.assertEqual(response["status"], "AVAILABLE")
        self.assertEqual(response["count"], 2)
        self.assertLessEqual(len(response["advisory_events"]), 2)

    def test_mongo_advisory_repository_indexes_and_retention(self):
        collection = FakeCollection()
        repository = MongoAdvisoryEventRepository(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection_name="ml_governance_advisory_events",
            retention_limit=2,
            client=FakeMongoClient(collection),
        )
        service = AdvisoryEventService(repository, self.config(retention=2))

        for index in range(3):
            actions = {**self.actions(), "drift_status": f"DRIFT-{index}"}
            service.emit_if_needed(actions, self.model(), self.lifecycle_context(), self.drift_context())

        self.assertEqual(collection.count_documents({"model_name": "python-logistic-fraud-model", "model_version": "advisory-test-v1"}), 2)
        self.assertIn(((("created_at", -1),), True), collection.indexes)
        self.assertIn(((("severity", 1),), True), collection.indexes)
        self.assertIn(((("model_name", 1), ("model_version", 1)), True), collection.indexes)

    def test_deduplicates_identical_advisory_within_window(self):
        service = AdvisoryEventService(FakeAdvisoryRepository(), self.config())

        first, first_status = service.emit_if_needed(self.actions(), self.model(), self.lifecycle_context(), self.drift_context())
        second, second_status = service.emit_if_needed(self.actions(), self.model(), self.lifecycle_context(), self.drift_context())
        response = service.history_response()

        self.assertIsNotNone(first)
        self.assertEqual(first_status, "persisted")
        self.assertIsNone(second)
        self.assertEqual(second_status, "deduplicated")
        self.assertEqual(response["count"], 1)

    def test_dedup_allows_identical_advisory_after_window(self):
        service = AdvisoryEventService(FakeAdvisoryRepository(fail=True), self.config())

        first, _ = service.emit_if_needed(self.actions(), self.model(), self.lifecycle_context(), self.drift_context())
        old_timestamp = (datetime.now(timezone.utc) - timedelta(minutes=6)).isoformat(timespec="seconds")
        service._events[0]["created_at"] = old_timestamp
        second, second_status = service.emit_if_needed(self.actions(), self.model(), self.lifecycle_context(), self.drift_context())

        self.assertIsNotNone(first)
        self.assertIsNotNone(second)
        self.assertEqual(second_status, "memory")
        self.assertEqual(service.history_response()["count"], 2)

    def test_confidence_context_is_bounded_and_omits_numeric_values(self):
        self.assertEqual(
            advisory_confidence_context(self.actions(confidence="HIGH"), self.drift_context(sample_size=10)),
            "LOW_SAMPLE",
        )
        self.assertEqual(
            advisory_confidence_context(self.actions(confidence="MEDIUM"), self.drift_context(reason="reference_profile_unavailable")),
            "PARTIAL_DATA",
        )
        self.assertEqual(
            advisory_confidence_context(self.actions(confidence="HIGH"), self.drift_context()),
            "SUFFICIENT_DATA",
        )
        event = build_advisory_event(self.actions(confidence="MEDIUM"), self.model(), self.lifecycle_context(), self.drift_context(confidence="MEDIUM"))

        self.assertEqual(event["advisory_confidence_context"], "STABLE_BASELINE")
        self.assertNotIn("sample_size", event)
        self.assertNotIn("observation_count", event)
        self.assertNotIn("min_observations", event)


class MlGovernanceActionsTest(unittest.TestCase):
    def model(self):
        return {
            "model_name": "python-logistic-fraud-model",
            "model_version": "actions-test-v1",
        }

    def drift(self, status: str, confidence: str, reason: str | None = None):
        return {
            "status": status,
            "confidence": confidence,
            "reason": reason,
            "evaluated_at": "2026-04-25T00:00:00+00:00",
            "sample_size": 100,
            "signals": [
                {
                    "drift_type": "feature_mean_shift",
                    "name": "recentTransactionCount",
                    "severity": status if status in {"WATCH", "DRIFT"} else "OK",
                    "statistic": "mean",
                    "reference": 1.0,
                    "inference": 1.2,
                    "absolute_difference": 0.2,
                    "relative_difference": 0.2,
                    "z_score": 2.5,
                }
            ],
        }

    def assert_action_contract(self, actions: dict):
        self.assertEqual(
            set(actions),
            {
                "severity",
                "confidence",
                "drift_status",
                "trend",
                "recommended_actions",
                "escalation",
                "automation_policy",
                "evaluated_at",
                "explanation",
            },
        )
        self.assertIn(actions["trend"], {"STABLE", "INCREASING", "DECREASING"})
        self.assertLessEqual(len(actions["recommended_actions"]), 5)
        self.assertEqual(
            actions["automation_policy"],
            {
                "advisory_only": True,
                "affects_scoring": False,
                "blocks_requests": False,
                "switches_model": False,
                "triggers_retraining": False,
            },
        )

    def test_unknown_insufficient_data_recommends_collection_only(self):
        actions = recommend_drift_actions(
            self.model(),
            self.drift("UNKNOWN", "LOW", reason="insufficient_data"),
        )

        self.assert_action_contract(actions)
        self.assertIn("COLLECT_MORE_DATA", actions["recommended_actions"])
        self.assertEqual(actions["escalation"], "NONE")
        self.assertEqual(actions["drift_status"], "UNKNOWN")

    def test_watch_medium_confidence_recommends_operator_review(self):
        actions = recommend_drift_actions(self.model(), self.drift("WATCH", "MEDIUM"))

        self.assert_action_contract(actions)
        self.assertIn("INVESTIGATE_DATA_SHIFT", actions["recommended_actions"])
        self.assertEqual(actions["escalation"], "OPERATOR_REVIEW")
        self.assertEqual(actions["severity"], "MEDIUM")

    def test_low_confidence_drift_validates_baseline_before_model_review(self):
        actions = recommend_drift_actions(
            self.model(),
            self.drift("DRIFT", "LOW", reason="non_production_reference_profile"),
        )

        self.assert_action_contract(actions)
        self.assertIn("INVESTIGATE_BASELINE_AND_DATA", actions["recommended_actions"])
        self.assertEqual(actions["escalation"], "OPERATOR_REVIEW")
        self.assertEqual(actions["severity"], "MEDIUM")

    def test_high_confidence_worsening_drift_escalates_model_review(self):
        history = [
            {"driftStatus": "DRIFT", "driftConfidence": "HIGH"},
            {"driftStatus": "WATCH", "driftConfidence": "MEDIUM"},
            {"driftStatus": "OK", "driftConfidence": "MEDIUM"},
        ]

        actions = recommend_drift_actions(self.model(), self.drift("DRIFT", "HIGH"), history)

        self.assert_action_contract(actions)
        self.assertEqual(actions["trend"], "INCREASING")
        self.assertEqual(actions["severity"], "CRITICAL")
        self.assertIn("ESCALATE_MODEL_REVIEW", actions["recommended_actions"])
        self.assertEqual(actions["escalation"], "MODEL_OWNER_REVIEW")
        self.assertEqual(actions["explanation"], "feature mean increased by 20% compared to reference profile")
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, json.dumps(actions))

    def test_lifecycle_context_avoids_causal_drift_claims(self):
        actions = recommend_drift_actions(self.model(), self.drift("DRIFT", "HIGH"))

        with_context = with_model_lifecycle_context(
            actions,
            {
                "current_model_version": "actions-test-v1",
                "model_loaded_at": "2026-04-25T00:00:00+00:00",
                "model_changed_recently": True,
                "recent_lifecycle_event_count": 2,
            },
        )

        self.assertIn("model_lifecycle", with_context)
        self.assertIn("drift was observed after recent model lifecycle activity", with_context["explanation"])
        self.assertNotIn("caused", with_context["explanation"].lower())
        self.assertNotIn("because of model", with_context["explanation"].lower())


class MlGovernanceEndpointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server.INFERENCE_PROFILE.reset()
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.FraudInferenceHandler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)

    def request(self, method: str, path: str, body: bytes | None = None, headers: dict[str, str] | None = None):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        payload = response.read()
        status = response.status
        response_headers = dict(response.getheaders())
        connection.close()
        return status, response_headers, payload

    def get_json(self, path: str):
        status, headers, payload = self.request("GET", path)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "application/json")
        return json.loads(payload.decode("utf-8"))

    def score(self, payload: bytes):
        status, _, body = self.request(
            "POST",
            "/v1/fraud/score",
            body=payload,
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 200)
        return json.loads(body.decode("utf-8"))

    def schema_snapshot(self, name: str):
        return json.loads((SNAPSHOT_DIR / name).read_text(encoding="utf-8"))

    def assert_json_contract(self, payload: dict, snapshot: dict):
        self.assertEqual(set(payload), set(snapshot["required"]))
        self.assert_field_types(payload, snapshot.get("types", {}))
        for field, values in snapshot.get("enum", {}).items():
            self.assertIn(payload[field], values)
        for field, required in snapshot.get("nestedRequired", {}).items():
            self.assertEqual(set(payload[field]), set(required))

    def assert_advisory_contract(self, payload: dict, snapshot: dict):
        self.assert_json_contract(payload, snapshot)
        for event in payload["advisory_events"]:
            self.assertEqual(set(event), set(snapshot["eventRequired"]))
            self.assert_field_types(event, snapshot.get("eventTypes", {}))
            for field, values in snapshot.get("eventEnum", {}).items():
                self.assertIn(event[field], values)
            for field, required in snapshot.get("eventNestedRequired", {}).items():
                self.assertEqual(set(event[field]), set(required))

    def assert_field_types(self, payload: dict, field_types: dict):
        for field, expected in field_types.items():
            if isinstance(expected, list):
                self.assertTrue(
                    any(self.matches_json_type(payload[field], candidate) for candidate in expected),
                    f"{field} expected one of {expected}, got {type(payload[field]).__name__}",
                )
            else:
                self.assertTrue(
                    self.matches_json_type(payload[field], expected),
                    f"{field} expected {expected}, got {type(payload[field]).__name__}",
                )

    def matches_json_type(self, value, expected_type: str):
        if expected_type == "boolean":
            return isinstance(value, bool)
        if expected_type == "integer":
            return isinstance(value, int) and not isinstance(value, bool)
        if expected_type == "number":
            return (isinstance(value, int) or isinstance(value, float)) and not isinstance(value, bool)
        if expected_type == "string":
            return isinstance(value, str)
        if expected_type == "object":
            return isinstance(value, dict)
        if expected_type == "array":
            return isinstance(value, list)
        if expected_type == "null":
            return value is None
        raise AssertionError(f"Unknown JSON schema snapshot type: {expected_type}")

    def assert_error_contract(self, status: int, payload: dict, message: str):
        self.assertEqual(
            set(payload),
            {"timestamp", "status", "error", "message", "details"},
        )
        self.assertEqual(payload["status"], status)
        self.assertEqual(payload["message"], message)
        self.assertIsInstance(payload["timestamp"], str)
        self.assertTrue(payload["timestamp"].endswith("Z"))
        self.assertIsInstance(payload["error"], str)
        self.assertIsInstance(payload["details"], list)
        serialized = json.dumps(payload)
        self.assertNotIn("Traceback", serialized)
        self.assertNotIn("JSONDecodeError", serialized)

    def test_governance_model_endpoint_exposes_lineage(self):
        payload = self.get_json("/governance/model")

        self.assertEqual(set(payload), {"model", "reference_profile", "inference_profile", "drift"})
        self.assertEqual(payload["model"]["model_name"], server.MODEL_NAME)
        self.assertEqual(payload["model"]["model_version"], server.MODEL_VERSION)
        self.assertEqual(
            payload["model"]["governance"]["scope"],
            "additive runtime oversight; scoring semantics unchanged",
        )
        self.assertIn("feature_set", payload["model"])

    def test_current_model_lifecycle_endpoint_returns_stable_schema(self):
        payload = self.get_json("/governance/model/current")

        self.assertEqual(payload["model_name"], server.MODEL_NAME)
        self.assertEqual(payload["model_version"], server.MODEL_VERSION)
        self.assertEqual(payload["lifecycle_mode"], "READ_ONLY")
        self.assertEqual(
            set(payload),
            {
                "model_name",
                "model_version",
                "model_family",
                "loaded_at",
                "artifact_path_or_id",
                "artifact_source",
                "artifact_checksum",
                "feature_set_version",
                "training_mode",
                "reference_profile_id",
                "runtime_environment",
                "lifecycle_mode",
            },
        )
        self.assertFalse(Path(str(payload["artifact_path_or_id"])).is_absolute())

    def test_lifecycle_endpoint_returns_bounded_safe_history(self):
        documents = [
            {
                "event_id": f"event-{index}",
                "event_type": "MODEL_LOADED",
                "occurred_at": f"2026-04-25T00:{index:02d}:00+00:00",
                "model_name": server.MODEL_NAME,
                "model_version": server.MODEL_VERSION,
                "previous_model_version": None,
                "source": "test",
                "reason": "loaded under test",
                "metadata_summary": {"lifecycle_mode": "READ_ONLY"},
            }
            for index in range(MAX_LIFECYCLE_HISTORY_LIMIT + 5)
        ]
        original_service = server.LIFECYCLE_SERVICE
        try:
            server.LIFECYCLE_SERVICE = ModelLifecycleService(
                FakeLifecycleRepository(documents=documents),
                LifecyclePersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_model_lifecycle_events",
                    retention_limit=MAX_LIFECYCLE_HISTORY_LIMIT + 10,
                ),
            )
            payload = self.get_json("/governance/model/lifecycle")
        finally:
            server.LIFECYCLE_SERVICE = original_service

        self.assertEqual(payload["status"], "AVAILABLE")
        self.assertEqual(payload["count"], MAX_LIFECYCLE_HISTORY_LIMIT)
        self.assertLessEqual(len(payload["lifecycle_events"]), MAX_LIFECYCLE_HISTORY_LIMIT)
        serialized = json.dumps(payload)
        self.assertNotIn("weights", serialized)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_lifecycle_endpoint_falls_back_when_mongo_unavailable(self):
        original_service = server.LIFECYCLE_SERVICE
        service = ModelLifecycleService(
            FakeLifecycleRepository(fail=True),
            LifecyclePersistenceConfig(
                mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                collection="ml_model_lifecycle_events",
                retention_limit=200,
            ),
        )
        service.record_event(
            "MODEL_LOADED",
            server.MODEL_LIFECYCLE,
            source="test",
            reason="loaded under test",
            metadata_summary={"lifecycle_mode": "READ_ONLY"},
        )
        try:
            server.LIFECYCLE_SERVICE = service
            payload = self.get_json("/governance/model/lifecycle")
        finally:
            server.LIFECYCLE_SERVICE = original_service

        self.assertEqual(payload["status"], "PARTIAL")
        self.assertEqual(payload["count"], 1)
        self.assertEqual(payload["lifecycle_events"][0]["event_type"], "MODEL_LOADED")

    def test_reference_profile_endpoint_marks_synthetic_quality(self):
        payload = self.get_json("/governance/profile/reference")
        reference = payload["reference_profile"]

        self.assertEqual(reference["source"], "synthetic")
        self.assertEqual(reference["reference_quality"], "SYNTHETIC")
        self.assertEqual(reference["sample_size"], 1000)
        self.assertIn("data_window", reference)
        self.assertIn("generated_by", reference)

    def test_inference_profile_updates_after_successful_scoring(self):
        before = self.get_json("/governance/profile/inference")["inference_profile"]["observation_count"]
        self.score(
            b'{"features":{"recentTransactionCount":1,"recentAmountSum":{"amount":45.0,"currency":"USD"},'
            b'"transactionVelocityPerMinute":0.05,"merchantFrequency7d":1,"deviceNovelty":false,'
            b'"countryMismatch":false,"proxyOrVpnDetected":false,"featureFlags":[]}}'
        )
        after = self.get_json("/governance/profile/inference")["inference_profile"]

        self.assertEqual(after["observation_count"], before + 1)
        self.assertIn("profile_started_at", after)
        self.assertIn("last_updated_at", after)
        self.assertIn("profile_uptime_seconds", after)
        serialized = json.dumps(after)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_governance_endpoints_return_bounded_aggregate_json(self):
        for path in (
            "/governance/profile/reference",
            "/governance/profile/inference",
            "/governance/drift",
            "/governance/drift/actions",
            "/governance/advisories",
            "/governance/history",
        ):
            payload = self.get_json(path)
            serialized = json.dumps(payload)
            self.assertLess(len(serialized), 50000)
            for field in SENSITIVE_FIELDS:
                self.assertNotIn(field, serialized)

    def test_history_endpoint_enforces_max_limit(self):
        documents = [
            {"snapshotId": f"snapshot-{index}", "createdAt": f"2026-04-25T00:{index:02d}:00+00:00"}
            for index in range(150)
        ]
        original_service = server.SNAPSHOT_SERVICE
        try:
            server.SNAPSHOT_SERVICE = GovernanceSnapshotService(
                FakeSnapshotRepository(documents=documents),
                GovernancePersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_governance_snapshots",
                    retention_limit=500,
                    snapshot_interval_requests=50,
                ),
            )
            payload = self.get_json("/governance/history?limit=999")
        finally:
            server.SNAPSHOT_SERVICE = original_service

        self.assertEqual(payload["status"], "AVAILABLE")
        self.assertEqual(payload["count"], MAX_HISTORY_LIMIT)
        self.assertEqual(len(payload["snapshots"]), MAX_HISTORY_LIMIT)

    def test_history_endpoint_returns_safe_unavailable_response(self):
        original_service = server.SNAPSHOT_SERVICE
        try:
            server.SNAPSHOT_SERVICE = GovernanceSnapshotService(
                UnavailableGovernanceSnapshotRepository(),
                GovernancePersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_governance_snapshots",
                    retention_limit=500,
                    snapshot_interval_requests=50,
                ),
            )
            payload = self.get_json("/governance/history")
        finally:
            server.SNAPSHOT_SERVICE = original_service

        self.assertEqual(payload["status"], "UNAVAILABLE")
        self.assertEqual(payload["count"], 1)
        self.assertLessEqual(len(payload["snapshots"]), 1)

    def test_advisories_endpoint_returns_bounded_safe_history(self):
        documents = [
            {
                "event_id": f"advisory-{index}",
                "event_type": "GOVERNANCE_DRIFT_ADVISORY",
                "severity": "HIGH",
                "drift_status": "DRIFT",
                "confidence": "HIGH",
                "advisory_confidence_context": "SUFFICIENT_DATA",
                "model_name": server.MODEL_NAME,
                "model_version": server.MODEL_VERSION,
                "lifecycle_context": {
                    "current_model_version": server.MODEL_VERSION,
                    "model_loaded_at": "2026-04-26T00:00:00+00:00",
                    "model_changed_recently": False,
                    "recent_lifecycle_event_count": 1,
                },
                "recommended_actions": ["ESCALATE_MODEL_REVIEW", "KEEP_SCORING_UNCHANGED"],
                "explanation": "score p95 increased by 18% compared to reference profile",
                "created_at": f"2026-04-26T00:{index:02d}:00+00:00",
            }
            for index in range(MAX_ADVISORY_LIMIT + 5)
        ]
        original_service = server.ADVISORY_SERVICE
        try:
            server.ADVISORY_SERVICE = AdvisoryEventService(
                FakeAdvisoryRepository(documents=documents),
                AdvisoryPersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_governance_advisory_events",
                    retention_limit=MAX_ADVISORY_LIMIT + 10,
                ),
            )
            payload = self.get_json("/governance/advisories?limit=999")
        finally:
            server.ADVISORY_SERVICE = original_service

        self.assertEqual(payload["status"], "AVAILABLE")
        self.assertEqual(payload["count"], MAX_ADVISORY_LIMIT)
        self.assertLessEqual(len(payload["advisory_events"]), MAX_ADVISORY_LIMIT)
        self.assert_advisory_contract(
            payload,
            self.schema_snapshot("governance_advisories_response.schema.json"),
        )
        serialized = json.dumps(payload)
        self.assertNotIn("raw feature", serialized.lower())
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, serialized)

    def test_advisories_endpoint_filters_by_severity_and_model_version(self):
        documents = [
            {
                "event_id": "advisory-high-current",
                "event_type": "GOVERNANCE_DRIFT_ADVISORY",
                "severity": "HIGH",
                "drift_status": "DRIFT",
                "confidence": "HIGH",
                "advisory_confidence_context": "SUFFICIENT_DATA",
                "model_name": server.MODEL_NAME,
                "model_version": server.MODEL_VERSION,
                "lifecycle_context": {
                    "current_model_version": server.MODEL_VERSION,
                    "model_loaded_at": "2026-04-26T00:00:00+00:00",
                    "model_changed_recently": False,
                    "recent_lifecycle_event_count": 1,
                },
                "recommended_actions": ["ESCALATE_MODEL_REVIEW"],
                "explanation": "score p95 increased by 18% compared to reference profile",
                "created_at": "2026-04-26T00:02:00+00:00",
            },
            {
                "event_id": "advisory-medium-current",
                "event_type": "GOVERNANCE_DRIFT_ADVISORY",
                "severity": "MEDIUM",
                "drift_status": "WATCH",
                "confidence": "MEDIUM",
                "advisory_confidence_context": "STABLE_BASELINE",
                "model_name": server.MODEL_NAME,
                "model_version": server.MODEL_VERSION,
                "lifecycle_context": {
                    "current_model_version": server.MODEL_VERSION,
                    "model_loaded_at": "2026-04-26T00:00:00+00:00",
                    "model_changed_recently": False,
                    "recent_lifecycle_event_count": 1,
                },
                "recommended_actions": ["INVESTIGATE_DATA_SHIFT"],
                "explanation": "feature mean increased by 20% compared to reference profile",
                "created_at": "2026-04-26T00:01:00+00:00",
            },
            {
                "event_id": "advisory-high-other",
                "event_type": "GOVERNANCE_DRIFT_ADVISORY",
                "severity": "HIGH",
                "drift_status": "DRIFT",
                "confidence": "HIGH",
                "advisory_confidence_context": "SUFFICIENT_DATA",
                "model_name": server.MODEL_NAME,
                "model_version": "other-v1",
                "lifecycle_context": {
                    "current_model_version": "other-v1",
                    "model_loaded_at": "2026-04-26T00:00:00+00:00",
                    "model_changed_recently": False,
                    "recent_lifecycle_event_count": 1,
                },
                "recommended_actions": ["ESCALATE_MODEL_REVIEW"],
                "explanation": "score p95 increased by 18% compared to reference profile",
                "created_at": "2026-04-26T00:00:00+00:00",
            },
        ]
        original_service = server.ADVISORY_SERVICE
        try:
            server.ADVISORY_SERVICE = AdvisoryEventService(
                FakeAdvisoryRepository(documents=documents),
                AdvisoryPersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_governance_advisory_events",
                    retention_limit=200,
                ),
            )
            payload = self.get_json(f"/governance/advisories?severity=HIGH&model_version={server.MODEL_VERSION}&limit=1")
        finally:
            server.ADVISORY_SERVICE = original_service

        self.assertEqual(payload["status"], "AVAILABLE")
        self.assertEqual(payload["count"], 1)
        self.assertEqual(payload["advisory_events"][0]["event_id"], "advisory-high-current")

    def test_drift_actions_endpoint_returns_advisory_guidance(self):
        documents = [
            {"driftStatus": "DRIFT", "driftConfidence": "LOW", "createdAt": "2026-04-25T00:02:00+00:00"},
            {"driftStatus": "WATCH", "driftConfidence": "LOW", "createdAt": "2026-04-25T00:01:00+00:00"},
        ]
        original_service = server.SNAPSHOT_SERVICE
        try:
            server.SNAPSHOT_SERVICE = GovernanceSnapshotService(
                FakeSnapshotRepository(documents=documents),
                GovernancePersistenceConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_governance_snapshots",
                    retention_limit=500,
                    snapshot_interval_requests=50,
                ),
            )
            payload = self.get_json("/governance/drift/actions")
        finally:
            server.SNAPSHOT_SERVICE = original_service

        self.assertEqual(
            set(payload),
            {
                "severity",
                "confidence",
                "drift_status",
                "trend",
                "recommended_actions",
                "escalation",
                "automation_policy",
                "evaluated_at",
                "explanation",
                "model_lifecycle",
            },
        )
        self.assertIn(payload["trend"], {"STABLE", "INCREASING", "DECREASING"})
        self.assertLessEqual(len(payload["recommended_actions"]), 5)
        self.assertTrue(payload["automation_policy"]["advisory_only"])
        self.assertFalse(payload["automation_policy"]["affects_scoring"])
        self.assertFalse(payload["automation_policy"]["blocks_requests"])
        self.assertFalse(payload["automation_policy"]["switches_model"])
        self.assertFalse(payload["automation_policy"]["triggers_retraining"])
        self.assertIn("current_model_version", payload["model_lifecycle"])
        self.assertNotIn("caused", payload["explanation"].lower())

    def test_drift_unknown_until_minimum_sample_size(self):
        server.INFERENCE_PROFILE.reset()

        drift = self.get_json("/governance/drift")

        self.assertEqual(drift["drift"]["status"], "UNKNOWN")
        self.assertEqual(drift["drift"]["confidence"], "LOW")
        self.assertEqual(drift["drift"]["reason"], "insufficient_data")
        self.assertEqual(drift["drift"]["inference_profile_status"], "RESET_RECENTLY")

    def test_existing_scoring_response_contract_remains_compatible(self):
        response = self.score(
            b'{"features":{"recentTransactionCount":8,"recentAmountSum":{"amount":7200.0,"currency":"USD"},'
            b'"transactionVelocityPerMinute":0.7,"merchantFrequency7d":9,"deviceNovelty":true,'
            b'"countryMismatch":true,"proxyOrVpnDetected":true,'
            b'"featureFlags":["DEVICE_NOVELTY","COUNTRY_MISMATCH","PROXY_OR_VPN","HIGH_VELOCITY"]}}'
        )

        self.assert_json_contract(
            response,
            self.schema_snapshot("fraud_score_response.schema.json"),
        )

    def test_error_responses_use_platform_contract(self):
        cases = [
            ("GET", "/missing", None, {}, 404, "Not found."),
            (
                "POST",
                "/v1/fraud/score",
                b'{"features":',
                {"Content-Type": "application/json"},
                400,
                "Malformed JSON request.",
            ),
            (
                "POST",
                "/v1/fraud/score",
                b'{"features":[]}',
                {"Content-Type": "application/json"},
                422,
                "Field 'features' must be an object.",
            ),
        ]

        for method, path, body, headers, expected_status, expected_message in cases:
            with self.subTest(path=path, status=expected_status):
                status, response_headers, raw_payload = self.request(method, path, body=body, headers=headers)
                payload = json.loads(raw_payload.decode("utf-8"))

                self.assertEqual(status, expected_status)
                self.assertEqual(response_headers["Content-Type"], "application/json")
                self.assert_error_contract(expected_status, payload, expected_message)

    def test_governance_metrics_remain_low_cardinality(self):
        self.get_json("/governance/drift")
        status, headers, payload = self.request("GET", "/metrics")
        self.assertEqual(status, 200)
        self.assertIn("text/plain", headers["Content-Type"])
        metrics = payload.decode("utf-8")

        self.assertIn("fraud_ml_governance_drift_status", metrics)
        self.assertIn("fraud_ml_governance_drift_action_recommendation", metrics)
        self.assertIn("fraud_ml_governance_drift_confidence", metrics)
        self.assertIn("fraud_ml_governance_reference_profile_loaded", metrics)
        self.assertIn("fraud_ml_governance_profile_observations_total", metrics)
        self.assertIn("fraud_ml_model_lifecycle_info", metrics)
        self.assertIn("fraud_ml_model_lifecycle_events_total", metrics)
        self.assertIn("fraud_ml_model_lifecycle_history_available", metrics)
        self.assertIn("fraud_ml_governance_advisory_events_emitted_total", metrics)
        self.assertIn("fraud_ml_governance_advisory_events_persisted_total", metrics)
        self.assertIn("fraud_ml_governance_advisory_persistence_failures_total", metrics)
        self.assertIn('fraud_ml_governance_drift_action_recommendation{model_name=', metrics)
        self.assertIn('fraud_ml_model_lifecycle_info{lifecycle_mode="READ_ONLY"', metrics)
        self.assertIn('severity=', metrics)
        self.assertNotIn("action=", metrics)
        self.assertNotIn("escalation=", metrics)
        self.assertNotIn("sample_size=", metrics)
        self.assertNotIn("feature_name=", metrics)
        self.assertNotIn("artifact_path=", metrics)
        self.assertNotIn("checksum=", metrics)
        self.assertNotIn("event_id=", metrics)
        self.assertNotIn("timestamp=", metrics)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, metrics)


if __name__ == "__main__":
    unittest.main()
