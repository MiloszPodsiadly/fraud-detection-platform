from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
ML_ROOT = ROOT / "ml-inference-service"
OFFLINE_ROOT = ML_ROOT / "offline_evaluation"
DOC = ROOT / "docs" / "architecture" / "python_ml_evaluation_suite.md"


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

    def assertNotInAnyOfflineFile(self, *terms: str):
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in OFFLINE_ROOT.rglob("*.py"))
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

    def assertDocContains(self, text: str):
        self.assertIn(text, self.doc())

    def doc(self) -> str:
        return DOC.read_text(encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
