from pathlib import Path
import unittest

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.dataset_schema import DatasetFormatError

try:
    from fdp123.fdp123_fixtures import jsonl, record
except ModuleNotFoundError:
    from fdp123_fixtures import jsonl, record


ROOT = Path(__file__).resolve().parents[4]
FDP123_ROOT = ROOT / "ml-inference-service" / "offline_evaluation" / "fdp123"
RUNNER = FDP123_ROOT / "run_fdp123_evaluation.py"
DOC = ROOT / "docs" / "architecture" / "python_ml_evaluation_suite.md"


class Fdp123ScopeGuardTest(unittest.TestCase):
    def test_noForbiddenSourceOfTruthImports(self):
        self.assertNotInAnyFdp123File("app.feedback.feedback_dataset", "app.data.dataset", "read_fdp102_jsonl")

    def test_fdp102ReaderDoesNotAcceptFdp123Metadata(self):
        with self.assertRaises(DatasetFormatError):
            read_fdp102_jsonl(jsonl(record()))

    def test_noRuntimeSurfaces(self):
        self.assertNotInAnyFdp123File("FastAPI", "Flask", "@app.route", "uvicorn", "@RestController", "@RequestMapping")

    def test_noDbKafkaNetworkOrScheduler(self):
        self.assertNotInAnyFdp123File("pymongo", "MongoClient", "KafkaProducer", "requests", "httpx", "urllib", "socket", "APScheduler", "celery")

    def test_noModelLifecycleOrStateMutationActions(self):
        self.assertNotInAnyFdp123File(
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
        self.assertNotIn("FastAPI", source)
        self.assertNotIn("APScheduler", source)
        self.assertNotIn("requests", source)
        self.assertNotIn("KafkaProducer", source)

    def test_noUiChangesForFdp123Evaluation(self):
        ui_root = ROOT / "analyst-console-ui"
        self.assertFalse(any("fdp123" in path.as_posix().lower() for path in ui_root.rglob("*") if path.is_file()))

    def test_docsDescribeFdp124Boundary(self):
        doc = DOC.read_text(encoding="utf-8")

        self.assertIn("FDP-124 consumes FDP-123 `DATASET_RECORD` rows", doc)
        self.assertIn("`DATASET_METADATA` is not an evaluation row", doc)
        self.assertIn("Only FDP-123 `DATASET_RECORD` lines are metric rows", doc)
        self.assertIn("FDP-102 and FDP-123 are separate input contracts", doc)
        self.assertIn("manual local offline runner", doc)
        self.assertIn("not a scheduler", doc)
        self.assertIn("not automatic report publishing", doc)
        self.assertIn("not a public export endpoint", doc)
        self.assertIn("External publishing requires a separate security and governance review", doc)
        self.assertIn("decisionReasonCodes", doc)
        self.assertIn("bounded machine-code", doc)
        self.assertIn("not notes", doc)
        self.assertIn("not raw evidence", doc)
        self.assertIn("Low sample size warnings are not model-quality conclusions", doc)

    def assertNotInAnyFdp123File(self, *terms: str):
        haystack = "\n".join(path.read_text(encoding="utf-8") for path in FDP123_ROOT.rglob("*.py"))
        for term in terms:
            self.assertNotIn(term, haystack)


if __name__ == "__main__":
    unittest.main()
