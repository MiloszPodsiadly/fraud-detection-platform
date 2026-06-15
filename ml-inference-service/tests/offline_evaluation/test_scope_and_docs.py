from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
ML_ROOT = ROOT / "ml-inference-service"
OFFLINE_ROOT = ML_ROOT / "offline_evaluation"
DOC = ROOT / "docs" / "architecture" / "python_ml_evaluation_suite.md"
MODEL_CARD_DOC = ROOT / "docs" / "architecture" / "model_card_v1.md"


class OfflineEvaluationScopeGuardTest(unittest.TestCase):
    def test_noProductionDbConnection(self):
        self.assertNotInAnyOfflineFile("pymongo", "MongoClient", "mongodb")

    def test_noNetworkCalls(self):
        self.assertNotInAnyOfflineFile("requests", "urllib", "httpx", "socket")

    def test_noKafkaProducer(self):
        self.assertNotInAnyOfflineFile("kafka", "KafkaProducer")

    def test_noScheduledJob(self):
        self.assertNotInAnyOfflineFile("schedule", "cron", "apscheduler")

    def test_noApiEndpoint(self):
        self.assertNotInAnyOfflineFile("FastAPI", "Flask", "@app.route", "uvicorn")

    def test_noUiFiles(self):
        ui_root = ROOT / "analyst-console-ui"
        self.assertFalse(any("offline_evaluation" in path.as_posix() for path in ui_root.rglob("*") if path.is_file()))

    def test_noModelArtifactMutation(self):
        self.assertNotInAnyOfflineFile("model_artifact.json", "write_model_artifact", "save(")

    def test_noThresholdConfigMutation(self):
        self.assertNotInAnyOfflineFile("threshold_config", "write_threshold", "thresholds.json", "PromotionThresholds")

    def test_noAlertServiceConnectorImports(self):
        self.assertNotInAnyOfflineFile("alert-service", "AlertClient", "frauddetection.alert")

    def test_noRetrainingModule(self):
        self.assertNotInAnyOfflineFile("retraining", "compare_retrained_model")

    def test_noPromotionWorkflow(self):
        self.assertNotInAnyOfflineFile("promotion_workflow", "promote_model", "switches_model")

    def test_noRecommendationModule(self):
        self.assertNotInAnyOfflineFile("recommendation_module", "AnalystRecommendation", "recommend_analyst_action")

    def test_fdp104DoesNotAddApiEndpoint(self):
        self.assertNotInAnyOfflineFile("FastAPI", "Flask", "@app.route", "uvicorn", "@RestController", "@RequestMapping")

    def test_fdp104DoesNotModifyOpenApi(self):
        openapi_root = ROOT / "docs" / "openapi"
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in openapi_root.rglob("*.yaml"))
        self.assertNotIn("model-card", haystack)
        self.assertNotIn("modelCard", haystack)

    def test_fdp104DoesNotAddUi(self):
        ui_root = ROOT / "analyst-console-ui"
        self.assertFalse(any("model_card" in path.as_posix() or "ModelCard" in path.as_posix() for path in ui_root.rglob("*") if path.is_file()))

    def test_fdp104DoesNotAddDashboard(self):
        self.assertNotInAnyOfflineFile("dashboard", "Dashboard")

    def test_fdp104DoesNotAddScheduledJob(self):
        self.assertNotInAnyOfflineFile("schedule", "cron", "APScheduler", "celery")

    def test_fdp104DoesNotReadProductionDb(self):
        self.assertNotInAnyOfflineFile("pymongo", "MongoClient", "mongodb", "model_registry")

    def test_fdp104DoesNotImportPymongoOrKafka(self):
        self.assertNotInAnyOfflineFile("pymongo", "MongoClient", "kafka", "KafkaProducer")

    def test_fdp104DoesNotCallNetwork(self):
        self.assertNotInAnyOfflineFile("requests", "httpx", "urllib", "socket")

    def test_fdp104DoesNotAddRetrainingCode(self):
        self.assertNotInAnyOfflineFile("retrain", "train_model")

    def test_fdp104DoesNotAddPromotionWorkflow(self):
        self.assertNotInAnyOfflineFile("promote_model", "promotion_workflow")

    def test_fdp104DoesNotMutateThresholdConfig(self):
        self.assertNotInAnyOfflineFile("threshold_config", "threshold_mutation")

    def test_fdp104DoesNotModifyModelArtifacts(self):
        self.assertNotInAnyOfflineFile("model_artifact.json", "write_model_artifact", "model_registry_write")

    def test_fdp104DoesNotModifyProductionScoring(self):
        self.assertNotInAnyOfflineFile("FraudInferenceHandler", "model_runtime", "score_completed")

    def test_fdp104DoesNotModifyKafkaEvents(self):
        common_events = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "common-events" / "src").rglob("*.java"))
        self.assertNotIn("ModelCard", common_events)

    def test_fdp104DoesNotModifyAlertServiceProjection(self):
        alert_service = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "alert-service" / "src" / "main").rglob("*.java"))
        self.assertNotIn("ModelCard", alert_service)

    def test_fdp104DoesNotAddRecommendationService(self):
        self.assertNotInAnyOfflineFile("recommendation_module", "AnalystRecommendation", "recommend_analyst_action")

    def test_fdp104DoesNotCallPaymentAuthorization(self):
        self.assertNotInAnyOfflineFile("payment_authorization", "approve_transaction", "decline_transaction", "block_transaction")

    def assertNotInAnyOfflineFile(self, *terms: str):
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in OFFLINE_ROOT.rglob("*.py"))
        haystack = haystack.replace("notAnalystRecommendation", "")
        for term in terms:
            self.assertNotIn(term, haystack)


class OfflineEvaluationDocumentationTest(unittest.TestCase):
    def test_docsMentionOfflineOnly(self):
        self.assertDocContains("FDP-103 is offline-only")

    def test_docsMentionConsumesOnlyFdp102Jsonl(self):
        self.assertDocContains("consumes only FDP-102 bounded JSONL export")

    def test_docsMentionNoProductionDbReads(self):
        self.assertDocContains("does not read production DBs")

    def test_docsMentionNoRetrainingPromotionThresholds(self):
        doc = self.doc()
        self.assertIn("does not retrain models", doc)
        self.assertIn("does not promote models", doc)
        self.assertIn("does not change thresholds", doc)

    def test_docsMentionNoApiUiKafkaScoringChanges(self):
        doc = self.doc()
        self.assertIn("does not emit Kafka events", doc)
        self.assertIn("does not expose API/UI", doc)
        self.assertIn("does not change production scoring", doc)

    def test_docsMentionLabelsNotGroundTruth(self):
        self.assertDocContains("Analyst labels are evaluation signals only")
        self.assertDocContains("They are not ground truth")

    def test_docsMentionFailedExportAborts(self):
        self.assertDocContains("Failed FDP-102 exports abort evaluation")

    def test_docsMentionNotEvaluationEligibleExcluded(self):
        self.assertDocContains("NOT_EVALUATION_ELIGIBLE is excluded from model-quality metrics")

    def test_docsMentionFailFastMalformedInputPolicy(self):
        self.assertDocContains("FDP-103 v1 fails fast on malformed or invalid schema input")

    def test_docsMentionPseudonymousInputReferencesStayInternal(self):
        self.assertDocContains("accepts FDP-102 pseudonymous input references only for parsing and deterministic ordering")
        self.assertDocContains("must not emit `evaluationRecordId`, `transactionReference`, `eval-`, or `txnref-`")

    def test_docsMentionStrictEngineStatusPolicy(self):
        self.assertDocContains("engineStatus as the source of truth for operational availability")
        self.assertDocContains("risk and score bucket fields must be absent")
        self.assertDocContains("are not ranked and are not high/low signals")

    def test_docsMentionModelCardOfflineOnly(self):
        self.assertModelCardDocContains("Model Card v1 is an offline governance artifact")

    def test_docsMentionModelCardNotPromotionApproval(self):
        self.assertModelCardDocContains("does not promote models")

    def test_docsMentionModelCardNotThresholdRecommendation(self):
        self.assertModelCardDocContains("does not recommend thresholds")

    def test_docsMentionModelCardNotProductionDecisioning(self):
        self.assertModelCardDocContains("does not approve, decline, or block")

    def test_docsMentionModelCardNotPaymentAuthorization(self):
        self.assertModelCardDocContains("does not authorize payments")

    def test_docsMentionApprovedForOnlyShadowCompareOffline(self):
        self.assertModelCardDocContains("approvedFor is limited to SHADOW and COMPARE")

    def test_docsMentionOfflineEvaluationIsNotApprovalTarget(self):
        self.assertModelCardDocContains("OFFLINE_EVALUATION is not an approval target")

    def test_docsMentionModelCardStrictValidation(self):
        doc = MODEL_CARD_DOC.read_text(encoding="utf-8")
        self.assertIn("Model Card v1 validates FDP-103 report identity", doc)
        self.assertIn("validates metric basis", doc)
        self.assertIn("validates dataset time basis", doc)
        self.assertIn("validates deduplication policy", doc)
        self.assertIn("validates metric numeric types and ranges", doc)
        self.assertIn("validates disagreementSummary with allowlisted keys", doc)

    def test_docsMentionModelIdentityIsSafeIdentifier(self):
        self.assertModelCardDocContains(
            "Model identity fields are safe identifiers, not URLs, paths, bucket URIs, registry endpoints, artifact locations, or secrets"
        )

    def test_docsMentionIntendedUseAndNonGoalsAreStrict(self):
        doc = MODEL_CARD_DOC.read_text(encoding="utf-8")
        self.assertIn("intendedUse is allowlisted", doc)
        self.assertIn("required notIntendedUse non-goals cannot be omitted", doc)

    def test_docsMentionDashboardIsFutureScope(self):
        self.assertModelCardDocContains("Dashboards and promotion workflows are future scopes")

    def assertDocContains(self, text: str):
        self.assertIn(text, self.doc())

    def assertModelCardDocContains(self, text: str):
        self.assertIn(text, MODEL_CARD_DOC.read_text(encoding="utf-8"))

    def doc(self) -> str:
        return DOC.read_text(encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
