import json
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

from app import server
from app.governance.actions import recommend_drift_actions
from app.governance.drift import CONFIDENCE_ORDER, MIN_OBSERVATIONS, evaluate_drift
from app.governance.lifecycle import (
    LIFECYCLE_MODE,
    MAX_LIFECYCLE_HISTORY_LIMIT,
    ModelLifecycleConfig,
    ModelLifecycleService,
    MongoLifecycleEventRepository,
    UnavailableLifecycleEventRepository,
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

RAW_ARTIFACT_FIELDS = (
    '"weights"',
    '"bias"',
    '"thresholds"',
    '"modelData"',
    "model_artifact.json:",
)


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


class FakeLifecycleRepository:
    def __init__(self, fail: bool = False, documents: list[dict] | None = None):
        self.fail = fail
        self.documents = documents or []
        self.persisted = []

    def persist(self, document: dict) -> None:
        if self.fail:
            raise RuntimeError("fake lifecycle persistence failure")
        self.persisted.append(document)

    def history(self, limit: int) -> list[dict]:
        if self.fail:
            raise RuntimeError("fake lifecycle history failure")
        return self.documents[:limit]

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


class MlModelLifecycleTest(unittest.TestCase):
    def config(self, retention: int = 2):
        return ModelLifecycleConfig(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection="ml_model_lifecycle_events",
            retention_limit=retention,
        )

    def lifecycle(self):
        return {
            "model_name": "python-logistic-fraud-model",
            "model_version": "lifecycle-test-v1",
            "model_family": "LOGISTIC_REGRESSION",
            "loaded_at": "2026-04-25T00:00:00+00:00",
            "artifact_path_or_id": "model_artifact.json",
            "artifact_source": "synthetic-fraud-scenarios",
            "artifact_checksum": "abc123",
            "feature_set_version": None,
            "training_mode": "production",
            "reference_profile_id": "2026-04-25.synthetic.v1",
            "runtime_environment": {
                "service": "ml-inference-service",
                "runtime": "python",
            },
            "lifecycle_mode": LIFECYCLE_MODE,
        }

    def event(self, index: int):
        return {
            "eventId": f"event-{index}",
            "eventType": "MODEL_METADATA_DETECTED",
            "occurredAt": f"2026-04-25T00:{index:02d}:00+00:00",
            "modelName": "python-logistic-fraud-model",
            "modelVersion": "lifecycle-test-v1",
            "previousModelVersion": None,
            "source": "ml-inference-service",
            "reason": "test",
            "metadataSummary": {"lifecycle_mode": LIFECYCLE_MODE},
        }

    def test_lifecycle_history_response_is_bounded(self):
        documents = [self.event(index) for index in range(150)]
        service = ModelLifecycleService(FakeLifecycleRepository(documents=documents), self.config())

        response = service.lifecycle_response(999, self.lifecycle())

        self.assertEqual(response["status"], "AVAILABLE")
        self.assertEqual(response["count"], MAX_LIFECYCLE_HISTORY_LIMIT)
        self.assertEqual(len(response["events"]), MAX_LIFECYCLE_HISTORY_LIMIT)

    def test_mongo_unavailable_falls_back_to_bounded_memory_history(self):
        service = ModelLifecycleService(UnavailableLifecycleEventRepository(), self.config())
        persisted = service.record_event(
            "MODEL_LOADED",
            self.lifecycle(),
            reason="runtime_startup",
            metadata_summary={"lifecycle_mode": LIFECYCLE_MODE},
        )

        response = service.lifecycle_response(10, self.lifecycle())

        self.assertFalse(persisted)
        self.assertEqual(response["status"], "PARTIAL")
        self.assertEqual(response["count"], 1)
        self.assertEqual(response["events"][0]["eventType"], "MODEL_LOADED")

    def test_lifecycle_mongo_retention_is_enforced_per_model_version(self):
        collection = FakeCollection()
        repository = MongoLifecycleEventRepository(
            mongodb_uri="mongodb://mongodb:27017/fraud_governance",
            collection_name="ml_model_lifecycle_events",
            retention_limit=2,
            client=FakeMongoClient(collection),
        )
        service = ModelLifecycleService(repository, self.config(retention=2))

        for index in range(3):
            service.record_event(
                "MODEL_METADATA_DETECTED",
                self.lifecycle(),
                reason=f"metadata-{index}",
                metadata_summary={"lifecycle_mode": LIFECYCLE_MODE},
            )

        self.assertEqual(collection.count_documents({"modelName": "python-logistic-fraud-model", "modelVersion": "lifecycle-test-v1"}), 2)
        self.assertIn(((("occurredAt", -1),), True), collection.indexes)

    def test_lifecycle_document_excludes_raw_artifact_and_secrets(self):
        service = ModelLifecycleService(FakeLifecycleRepository(), self.config())
        event = service.build_event(
            "MODEL_METADATA_DETECTED",
            self.lifecycle(),
            source="ml-inference-service",
            reason="metadata_available",
            metadata_summary={
                "lifecycle_mode": LIFECYCLE_MODE,
                "artifact_source": "synthetic-fraud-scenarios",
                "artifact_path": "C:/secret/model_artifact.json",
                "checksum": "not-allowed",
            },
        )

        serialized = json.dumps(event, default=str)
        self.assertNotIn("artifact_path", serialized)
        self.assertNotIn("C:/secret", serialized)
        self.assertNotIn("checksum", serialized)
        for field in SENSITIVE_FIELDS + RAW_ARTIFACT_FIELDS:
            self.assertNotIn(field, serialized)


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
                "model_lifecycle",
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
        self.assertEqual(
            set(actions["model_lifecycle"]),
            {
                "current_model_version",
                "model_loaded_at",
                "model_changed_recently",
                "recent_lifecycle_event_count",
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

    def test_drift_action_lifecycle_context_avoids_causal_wording(self):
        actions = recommend_drift_actions(
            self.model(),
            self.drift("DRIFT", "HIGH"),
            model_lifecycle={
                "current_model_version": "actions-test-v1",
                "model_loaded_at": "2026-04-25T00:00:00+00:00",
                "model_changed_recently": True,
                "recent_lifecycle_event_count": 3,
            },
        )

        self.assertTrue(actions["model_lifecycle"]["model_changed_recently"])
        wording = json.dumps(actions).lower()
        self.assertNotIn("caused", wording)
        self.assertNotIn("because of model", wording)
        self.assertNotIn("due to model", wording)


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
        lifecycle = payload["model_lifecycle"]

        self.assertEqual(
            set(lifecycle),
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
        self.assertEqual(lifecycle["lifecycle_mode"], "READ_ONLY")
        self.assertEqual(lifecycle["model_name"], server.MODEL_NAME)
        self.assertEqual(lifecycle["model_version"], server.MODEL_VERSION)
        self.assertNotIn("/", lifecycle["artifact_path_or_id"])
        self.assertNotIn("\\", lifecycle["artifact_path_or_id"])
        self.assertNotIn(":", lifecycle["artifact_path_or_id"])
        serialized = json.dumps(payload)
        for field in SENSITIVE_FIELDS + RAW_ARTIFACT_FIELDS:
            self.assertNotIn(field, serialized)

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
            "/governance/history",
            "/governance/model/current",
            "/governance/model/lifecycle",
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

    def test_lifecycle_endpoint_enforces_max_limit(self):
        documents = [
            {
                "eventId": f"event-{index}",
                "eventType": "MODEL_METADATA_DETECTED",
                "occurredAt": f"2026-04-25T00:{index:02d}:00+00:00",
                "modelName": server.MODEL_NAME,
                "modelVersion": server.MODEL_VERSION,
                "previousModelVersion": None,
                "source": "ml-inference-service",
                "reason": "test",
                "metadataSummary": {"lifecycle_mode": "READ_ONLY"},
            }
            for index in range(150)
        ]
        original_service = server.LIFECYCLE_SERVICE
        try:
            server.LIFECYCLE_SERVICE = ModelLifecycleService(
                FakeLifecycleRepository(documents=documents),
                ModelLifecycleConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_model_lifecycle_events",
                    retention_limit=200,
                ),
            )
            payload = self.get_json("/governance/model/lifecycle?limit=999")
        finally:
            server.LIFECYCLE_SERVICE = original_service

        self.assertEqual(payload["status"], "AVAILABLE")
        self.assertEqual(payload["count"], MAX_LIFECYCLE_HISTORY_LIMIT)
        self.assertEqual(len(payload["events"]), MAX_LIFECYCLE_HISTORY_LIMIT)

    def test_lifecycle_endpoint_returns_partial_when_mongo_unavailable(self):
        original_service = server.LIFECYCLE_SERVICE
        try:
            service = ModelLifecycleService(
                UnavailableLifecycleEventRepository(),
                ModelLifecycleConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_model_lifecycle_events",
                    retention_limit=200,
                ),
            )
            service.record_event(
                "MODEL_LOADED",
                server.MODEL_LIFECYCLE,
                reason="runtime_startup",
                metadata_summary={"lifecycle_mode": "READ_ONLY"},
            )
            server.LIFECYCLE_SERVICE = service
            payload = self.get_json("/governance/model/lifecycle")
        finally:
            server.LIFECYCLE_SERVICE = original_service

        self.assertEqual(payload["status"], "PARTIAL")
        self.assertEqual(payload["count"], 1)
        self.assertEqual(payload["events"][0]["eventType"], "MODEL_LOADED")

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
        self.assertLessEqual(len(payload["explanation"]), 120)
        self.assertEqual(
            set(payload["model_lifecycle"]),
            {
                "current_model_version",
                "model_loaded_at",
                "model_changed_recently",
                "recent_lifecycle_event_count",
            },
        )
        wording = json.dumps(payload).lower()
        self.assertNotIn("caused", wording)
        self.assertNotIn("because of model", wording)
        self.assertNotIn("due to model", wording)

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

        self.assertEqual(
            set(response),
            {
                "available",
                "fraudScore",
                "riskLevel",
                "modelName",
                "modelVersion",
                "inferenceTimestamp",
                "reasonCodes",
                "scoreDetails",
                "explanationMetadata",
                "fallbackReason",
            },
        )

    def test_lifecycle_persistence_failure_does_not_change_scoring_contract(self):
        original_service = server.LIFECYCLE_SERVICE
        try:
            server.LIFECYCLE_SERVICE = ModelLifecycleService(
                UnavailableLifecycleEventRepository(),
                ModelLifecycleConfig(
                    mongodb_uri="mongodb://mongodb:27017/fraud_governance",
                    collection="ml_model_lifecycle_events",
                    retention_limit=200,
                ),
            )
            response = self.score(
                b'{"features":{"recentTransactionCount":8,"recentAmountSum":{"amount":7200.0,"currency":"USD"},'
                b'"transactionVelocityPerMinute":0.7,"merchantFrequency7d":9,"deviceNovelty":true,'
                b'"countryMismatch":true,"proxyOrVpnDetected":true,'
                b'"featureFlags":["DEVICE_NOVELTY","COUNTRY_MISMATCH","PROXY_OR_VPN","HIGH_VELOCITY"]}}'
            )
        finally:
            server.LIFECYCLE_SERVICE = original_service

        self.assertEqual(
            set(response),
            {
                "available",
                "fraudScore",
                "riskLevel",
                "modelName",
                "modelVersion",
                "inferenceTimestamp",
                "reasonCodes",
                "scoreDetails",
                "explanationMetadata",
                "fallbackReason",
            },
        )

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
        self.assertIn('fraud_ml_governance_drift_action_recommendation{model_name=', metrics)
        self.assertIn('severity=', metrics)
        self.assertNotIn("action=", metrics)
        self.assertNotIn("escalation=", metrics)
        self.assertNotIn("artifact_path=", metrics)
        self.assertNotIn("checksum=", metrics)
        self.assertNotIn("event_id=", metrics)
        self.assertNotIn("timestamp=", metrics)
        self.assertNotIn("reason=", metrics)
        self.assertNotIn("sample_size=", metrics)
        self.assertNotIn("feature_name=", metrics)
        for field in SENSITIVE_FIELDS:
            self.assertNotIn(field, metrics)


if __name__ == "__main__":
    unittest.main()
