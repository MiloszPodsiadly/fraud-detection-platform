from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
OFFLINE_ROOT = ROOT / "ml-inference-service" / "offline_evaluation"
SUMMARY_DOC = ROOT / "docs" / "architecture" / "shadow_performance_summary.md"
GLOSSARY_DOC = ROOT / "docs" / "product" / "fraud_intelligence_glossary.md"


class ShadowPerformanceScopeGuardTest(unittest.TestCase):
    def test_fdp105DoesNotAddApiOrOpenApiSurface(self):
        self.assertNotInAnyOfflineFile("FastAPI", "Flask", "@app.route", "uvicorn", "@RestController", "@RequestMapping", "openapi")

    def test_fdp105DoesNotAddDashboardOrUiSurface(self):
        ui_root = ROOT / "analyst-console-ui"
        self.assertFalse(any("shadow_performance" in path.as_posix() for path in ui_root.rglob("*") if path.is_file()))
        self.assertNotInAnyOfflineFile("dashboard", "Dashboard")

    def test_fdp105DoesNotAddSchedulersDbKafkaOrNetwork(self):
        self.assertNotInAnyOfflineFile("APScheduler", "celery", "pymongo", "MongoClient", "KafkaProducer", "requests", "httpx")

    def test_fdp105DoesNotMutateScoringRegistryArtifactsOrThresholds(self):
        self.assertNotInAnyOfflineFile(
            "FraudInferenceHandler",
            "model_registry_write",
            "write_model_artifact",
            "model_artifact.json",
            "threshold_config",
            "write_threshold",
        )

    def test_fdp105DoesNotAddTrainingPromotionOrRecommendationFlow(self):
        self.assertNotInAnyOfflineFile(
            "train_model",
            "retraining",
            "promote_model",
            "promotion_workflow",
            "recommendation_module",
            "recommend_analyst_action",
        )

    def test_fdp105DoesNotCallPaymentOrStateMutationFlows(self):
        self.assertNotInAnyOfflineFile(
            "payment_authorization",
            "approve_transaction",
            "decline_transaction",
            "block_transaction",
            "fraud_case_status",
            "alert_severity",
        )

    def test_docsDescribeOfflineDiagnosticOnlyBoundary(self):
        doc = SUMMARY_DOC.read_text(encoding="utf-8")

        self.assertIn("Shadow Performance Summary v2 is an offline diagnostic artifact", doc)
        self.assertIn("accepts only validated", doc)
        self.assertIn("FDP-123/FDP-124/FDP-126 Platform Recommendation Evaluation Card v1", doc)
        self.assertIn("does not recreate the removed FDP-102/FDP-103 Platform Recommendation Evaluation Card path", " ".join(doc.split()))
        self.assertIn("evaluation population and sample-size context", doc)

    def test_docsDescribeNonGoalsAndNoRuntimeSurface(self):
        doc = SUMMARY_DOC.read_text(encoding="utf-8")

        self.assertIn("does not approve model promotion", doc)
        self.assertIn("does not recommend thresholds", doc)
        self.assertIn("does not authorize payments", doc)
        self.assertIn("does not expose API, OpenAPI, UI, or dashboards", doc)
        self.assertIn("does not create", doc)
        self.assertIn("scheduled jobs, DB writes, Kafka messages, scoring changes, registry writes", doc)
        self.assertIn("model artifact mutations", doc)

    def test_docsDescribePopulationContextAsRequired(self):
        doc = SUMMARY_DOC.read_text(encoding="utf-8")

        self.assertIn("alertRecommendedPrecision", doc)
        self.assertIn("alertRecommendedRecall", doc)
        self.assertIn("falsePositiveRate", doc)
        self.assertIn("falseNegativeRate", doc)
        self.assertIn("unavailable metrics are not", doc)
        self.assertIn("avoid performance overclaim", doc)
        self.assertIn("small samples", doc)

    def test_glossaryMentionsShadowPerformanceSummary(self):
        glossary = GLOSSARY_DOC.read_text(encoding="utf-8")

        self.assertIn("Shadow Performance Summary v2", glossary)
        self.assertIn("includes population context and metric availability objects", glossary)
        self.assertIn("derived only from validated FDP-126 Platform Recommendation Evaluation Card fields", glossary)

    def assertNotInAnyOfflineFile(self, *terms: str):
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in OFFLINE_ROOT.rglob("*.py"))
        for term in terms:
            self.assertNotIn(term, haystack)


if __name__ == "__main__":
    unittest.main()
