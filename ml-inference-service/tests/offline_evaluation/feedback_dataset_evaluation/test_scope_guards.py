from pathlib import Path
import importlib
import unittest


ROOT = Path(__file__).resolve().parents[4]
FEEDBACK_DATASET_EVALUATION_ROOT = ROOT / "ml-inference-service" / "offline_evaluation" / "feedback_dataset_evaluation"
RUNNER = FEEDBACK_DATASET_EVALUATION_ROOT / "run_feedback_dataset_evaluation.py"
DOC = ROOT / "docs" / "architecture" / "python_ml_evaluation_suite.md"


class FeedbackDatasetEvaluationScopeGuardTest(unittest.TestCase):
    def test_noForbiddenSourceOfTruthImports(self):
        self.assertNotInAnyFeedbackDatasetEvaluationFile("app.feedback.feedback_dataset", "app.data.dataset", "read_fdp102_jsonl")

    def test_removedFdp102ReaderCannotResolve(self):
        with self.assertRaises(ModuleNotFoundError):
            importlib.import_module("offline_evaluation.dataset_reader")

    def test_noRuntimeSurfaces(self):
        self.assertNotInAnyFeedbackDatasetEvaluationFile("FastAPI", "Flask", "@app.route", "uvicorn", "@RestController", "@RequestMapping")

    def test_noDbKafkaNetworkOrScheduler(self):
        self.assertNotInAnyFeedbackDatasetEvaluationFile("pymongo", "MongoClient", "KafkaProducer", "requests", "httpx", "urllib", "socket", "APScheduler", "celery")

    def test_noModelLifecycleOrStateMutationActions(self):
        self.assertNotInAnyFeedbackDatasetEvaluationFile(
            "train_model",
            "retraining",
            "promote_model",
            "promotion_workflow",
            "write_threshold",
            "approve_transaction",
            "decline_transaction",
            "block_transaction",
            "fraud_case_status",
        )

    def test_manualRunnerRemainsLocalOfflineOnly(self):
        source = RUNNER.read_text(encoding="utf-8")

        self.assertIn("--input", source)
        self.assertIn("--output-dir", source)
        self.assertIn("--generated-at", source)
        self.assertIn("--allow-output-root", source)
        self.assertNotIn("FastAPI", source)
        self.assertNotIn("APScheduler", source)
        self.assertNotIn("requests", source)
        self.assertNotIn("KafkaProducer", source)

    def test_noUiChangesForFeedbackDatasetEvaluation(self):
        ui_root = ROOT / "analyst-console-ui"
        self.assertFalse(any("feedback_dataset_evaluation" in path.as_posix().lower() for path in ui_root.rglob("*") if path.is_file()))

    def test_docsDescribeFdp124Boundary(self):
        doc = DOC.read_text(encoding="utf-8")

        self.assertIn("FDP-124 consumes feedback dataset `DATASET_RECORD` rows", doc)
        self.assertIn("`DATASET_METADATA` is not an evaluation row", doc)
        self.assertIn("Only feedback dataset `DATASET_RECORD` lines are metric rows", doc)
        self.assertIn("Feedback dataset evaluation is not a permissive dual-format parser", doc)
        self.assertIn("manual local offline runner", doc)
        self.assertIn("not a scheduler", doc)
        self.assertIn("not automatic report publishing", doc)
        self.assertIn("not a public export endpoint", doc)
        self.assertIn("External publishing requires a separate security and governance review", doc)
        self.assertIn("manifest-last local artifact pattern", doc)
        self.assertIn("A report set is considered complete only when `manifest.json` exists", doc)
        self.assertIn("sha256", doc)
        self.assertIn("sizeBytes", doc)
        self.assertIn("scheduled generation or external", doc)
        self.assertIn("decisionReasonCodes", doc)
        self.assertIn("bounded machine-code", doc)
        self.assertIn("not notes", doc)
        self.assertIn("not raw evidence", doc)
        self.assertIn("Low sample size warnings are not model-quality conclusions", doc)

    def assertNotInAnyFeedbackDatasetEvaluationFile(self, *terms: str):
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in FEEDBACK_DATASET_EVALUATION_ROOT.rglob("*.py"))
        for term in terms:
            self.assertNotIn(term, haystack)


if __name__ == "__main__":
    unittest.main()
