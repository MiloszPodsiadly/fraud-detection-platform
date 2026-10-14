import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[5]
PACKAGE_ROOT = ROOT / "ml-inference-service" / "offline_evaluation" / "feedback_dataset_evaluation" / "model_card"
SHARED_ROOT = PACKAGE_ROOT.parent
ALERT_SOURCE_ROOT = ROOT / "alert-service" / "src" / "main" / "java"
FRONTEND_SOURCE_ROOT = ROOT / "analyst-console-ui" / "src"


class ModelCardSingleSourceArchitectureTest(unittest.TestCase):
    def test_modelCardHasOneCanonicalWriter(self):
        source = self._python_source(PACKAGE_ROOT)

        self.assertEqual(1, source.count("def write_model_card_artifacts("))
        self.assertEqual(1, source.count("os.replace(staging_dir, destination)"))
        self.assertIn("write_model_card_artifacts", (PACKAGE_ROOT / "generator.py").read_text(encoding="utf-8"))

    def test_modelCardUsesSharedSafetyPolicyWithoutEvaluationCardOwnership(self):
        source = self._python_source(PACKAGE_ROOT)
        shared = (SHARED_ROOT / "governance_artifact_safety.py").read_text(encoding="utf-8")

        self.assertIn("governance_artifact_safety", source)
        self.assertNotIn("evaluation_card.safety_policy", source)
        self.assertIn("class GovernanceArtifactSafetyPolicyError", shared)
        self.assertFalse((PACKAGE_ROOT.parent / "evaluation_card" / "safety_policy.py").exists())

    def test_modelCardConsumesOnlyExactModelEvaluationEvidence(self):
        generator = (PACKAGE_ROOT / "generator.py").read_text(encoding="utf-8")

        self.assertIn("read_validated_model_evaluation_artifact_set", generator)
        self.assertNotIn("evaluation_card", generator)
        self.assertNotIn("shadow_performance", generator)
        self.assertNotIn("promotion_review_readiness", generator)
        self.assertNotIn("FEEDBACK_DATASET_OFFLINE_EVALUATION_V1", generator)

    def test_modelCardHasOneCanonicalReadEndpointAndFrontendHook(self):
        java_source = self._source(ALERT_SOURCE_ROOT, "*.java")
        frontend_source = self._source(FRONTEND_SOURCE_ROOT, "*.js") + self._source(FRONTEND_SOURCE_ROOT, "*.jsx")

        self.assertEqual(1, java_source.count('@RequestMapping("/api/v1/governance/model-card")'))
        self.assertEqual(1, frontend_source.count("export function useModelCard("))
        self.assertEqual(1, frontend_source.count('request("/api/v1/governance/model-card/current"'))

    def test_modelCardCannotMutatePromotionRuntimeOrScoringState(self):
        python_source = self._python_source(PACKAGE_ROOT)
        java_source = self._source(
            ALERT_SOURCE_ROOT / "com" / "frauddetection" / "alert" / "governance" / "modelcard",
            "*.java",
        )
        combined = python_source + java_source

        for forbidden in (
                "promotion_workflow",
                "promote_model",
                "ScoringMode",
                "ModelRegistry",
                "KafkaTemplate",
                "MongoTemplate",
                "repository.save",
                "write_threshold",
                "train_model",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, combined)

    @staticmethod
    def _python_source(root: Path) -> str:
        return ModelCardSingleSourceArchitectureTest._source(root, "*.py")

    @staticmethod
    def _source(root: Path, pattern: str) -> str:
        return "\n".join(path.read_text(encoding="utf-8") for path in root.rglob(pattern))


if __name__ == "__main__":
    unittest.main()
