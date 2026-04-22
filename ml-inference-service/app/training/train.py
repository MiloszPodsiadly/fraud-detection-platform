from __future__ import annotations

import json
from pathlib import Path

from app.data.dataset import Dataset
from app.data.splitting import split_dataset
from app.evaluation.evaluate import evaluate_scores
from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.xgboost_model import XGBoostFraudModel


def train(dataset: Dataset, epochs: int, learning_rate: float) -> tuple[float, dict[str, float]]:
    """Train the existing logistic baseline with batch gradient descent."""
    feature_rows = FeaturePipeline().fit(dataset).transform(dataset)
    model = LogisticFraudModel()
    model.fit(feature_rows, dataset.y, epochs=epochs, learning_rate=learning_rate)
    return model.bias, model.weights


def train_with_evaluation(
        dataset: Dataset,
        epochs: int,
        learning_rate: float,
) -> tuple[float, dict[str, float], dict[str, object]]:
    """Train on train split, tune threshold on validation, and report test metrics."""
    splits = split_dataset(dataset)
    feature_pipeline = FeaturePipeline().fit(splits.train)
    train_rows = feature_pipeline.transform(splits.train)
    validation_rows = feature_pipeline.transform(splits.validation)
    test_rows = feature_pipeline.transform(splits.test)

    model = LogisticFraudModel()
    model.fit(train_rows, splits.train.y, epochs=epochs, learning_rate=learning_rate)
    validation_scores = [model.predict_proba(features) for features in validation_rows]
    validation_report = evaluate_scores(splits.validation.y, validation_scores)
    selected_threshold = float(validation_report["optimalThreshold"]["threshold"])
    test_scores = [model.predict_proba(features) for features in test_rows]
    test_report = evaluate_scores(splits.test.y, test_scores, thresholds=[selected_threshold])
    test_report["validationEvaluation"] = validation_report
    test_report["splitMetadata"] = splits.metadata
    test_report["selectedThresholdSource"] = "validation"
    return model.bias, model.weights, test_report


def train_model(dataset: Dataset, model_type: str, epochs: int, learning_rate: float) -> LogisticFraudModel | XGBoostFraudModel:
    """Train the configured model type."""
    feature_rows = FeaturePipeline().fit(dataset).transform(dataset)
    if model_type == "logistic":
        model = LogisticFraudModel()
        model.fit(feature_rows, dataset.y, epochs=epochs, learning_rate=learning_rate)
        return model
    if model_type == "xgboost":
        model = XGBoostFraudModel()
        model.fit(feature_rows, dataset.y)
        return model
    raise ValueError("model_type must be 'logistic' or 'xgboost'.")


def sigmoid(value: float) -> float:
    """Apply the logistic sigmoid function."""
    return 1.0 / (1.0 + pow(2.718281828459045, -value))


def write_artifact(
        path: Path,
        bias: float,
        weights: dict[str, float],
        examples: int,
        model_type: str = "logistic",
        evaluation: dict[str, object] | None = None,
) -> None:
    """Persist a trained model artifact compatible with the inference service."""
    artifact = {
        "modelName": "python-logistic-fraud-model",
        "modelVersion": "2026-04-21.trained.v1",
        "modelType": model_type,
        "modelFamily": "LOGISTIC_REGRESSION",
        "bias": bias,
        "weights": weights,
        "thresholds": {
            "medium": 0.45,
            "high": 0.75,
            "critical": 0.90,
        },
        "training": {
            "source": "synthetic-fraud-scenarios",
            "algorithm": "batch-gradient-descent",
            "examples": examples,
        },
        "featureSchema": list(FeaturePipeline.FEATURE_NAMES),
        "featureImportance": {name: abs(weight) for name, weight in weights.items()},
        "evaluation": evaluation or {},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")
