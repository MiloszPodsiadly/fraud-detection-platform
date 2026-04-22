from __future__ import annotations

import json
from pathlib import Path

from app.data.dataset import Dataset
from app.data.splitting import split_dataset
from app.evaluation.evaluate import evaluate_scores
from app.features.feature_pipeline import FeaturePipeline
from app.models.logistic_model import LogisticFraudModel
from app.models.xgboost_model import XGBoostFraudModel


def train(
        dataset: Dataset,
        epochs: int,
        learning_rate: float,
        training_mode: str = "production",
) -> tuple[float, dict[str, float]]:
    """Train the existing logistic baseline with batch gradient descent."""
    pipeline = FeaturePipeline().fit(dataset)
    feature_rows = pipeline.transform(dataset, mode=training_mode)
    _validate_feature_set(feature_rows, pipeline.get_training_features(training_mode), training_mode)
    model = LogisticFraudModel()
    model.fit(feature_rows, dataset.y, epochs=epochs, learning_rate=learning_rate)
    return model.bias, model.weights


def train_with_evaluation(
        dataset: Dataset,
        epochs: int,
        learning_rate: float,
        training_mode: str = "production",
        model_type: str = "logistic",
) -> tuple[float, dict[str, float], dict[str, object]]:
    """Train on train split, tune threshold on validation, and report test metrics."""
    model, evaluation = train_model_with_evaluation(dataset, model_type, epochs, learning_rate, training_mode)
    weights = getattr(model, "weights", None)
    if not weights:
        weights = model.feature_importance()
    return getattr(model, "bias", 0.0), dict(weights), evaluation


def train_model_with_evaluation(
        dataset: Dataset,
        model_type: str,
        epochs: int,
        learning_rate: float,
        training_mode: str = "production",
) -> tuple[LogisticFraudModel | XGBoostFraudModel, dict[str, object]]:
    """Train and evaluate any supported model through the same lifecycle."""
    splits = split_dataset(dataset, mode="temporal")
    model, test_report = _train_on_splits(splits, model_type, epochs, learning_rate, training_mode)
    out_of_time_splits = split_dataset(dataset, mode="out_of_time", cutoff_ratio=0.6)
    _, out_of_time_report = _train_on_splits(out_of_time_splits, model_type, epochs, learning_rate, training_mode)
    test_report["outOfTimeEvaluation"] = {
        "prAuc": out_of_time_report["prAuc"],
        "rocAuc": out_of_time_report["rocAuc"],
        "optimalThreshold": out_of_time_report["optimalThreshold"],
        "costEvaluation": out_of_time_report["costEvaluation"],
        "budgetEvaluation": out_of_time_report["budgetEvaluation"],
        "segmentEvaluation": out_of_time_report.get("segmentEvaluation", {}),
        "splitMetadata": out_of_time_report["splitMetadata"],
    }
    test_report["evaluationComparison"] = {
        "temporalPrAuc": test_report["prAuc"],
        "outOfTimePrAuc": out_of_time_report["prAuc"],
        "prAucDelta": round(float(test_report["prAuc"]) - float(out_of_time_report["prAuc"]), 6),
    }
    test_report["stabilityAssessment"] = _stability_assessment(test_report, out_of_time_report)
    return model, test_report


def _train_on_splits(
        splits,
        model_type: str,
        epochs: int,
        learning_rate: float,
        training_mode: str,
) -> tuple[LogisticFraudModel | XGBoostFraudModel, dict[str, object]]:
    feature_pipeline = FeaturePipeline().fit(splits.train)
    feature_set = feature_pipeline.get_training_features(training_mode)
    train_rows = feature_pipeline.transform(splits.train, mode=training_mode)
    validation_rows = feature_pipeline.transform(splits.validation, mode=training_mode)
    test_rows = feature_pipeline.transform(splits.test, mode=training_mode)
    _validate_feature_set(train_rows, feature_set, training_mode)
    _validate_feature_set(validation_rows, feature_set, training_mode)
    _validate_feature_set(test_rows, feature_set, training_mode)

    model = _new_model(model_type, training_mode, feature_set)
    if isinstance(model, LogisticFraudModel):
        model.fit(train_rows, splits.train.y, epochs=epochs, learning_rate=learning_rate)
    else:
        model.fit(train_rows, splits.train.y)
        model.weights = model.feature_importance()
    validation_scores = [model.predict_proba(features) for features in validation_rows]
    validation_report = evaluate_scores(splits.validation.y, validation_scores, segment_rows=splits.validation.X)
    selected_threshold = float(validation_report["optimalThreshold"]["threshold"])
    test_scores = [model.predict_proba(features) for features in test_rows]
    test_report = evaluate_scores(splits.test.y, test_scores, thresholds=[selected_threshold], segment_rows=splits.test.X)
    test_report["validationEvaluation"] = validation_report
    test_report["splitMetadata"] = splits.metadata
    test_report["selectedThresholdSource"] = "validation"
    test_report["trainingMode"] = training_mode
    test_report["featureSetUsed"] = feature_set
    test_report["modelType"] = model_type
    test_report["modelFamily"] = model.model_family
    return model, test_report


def train_model(
        dataset: Dataset,
        model_type: str,
        epochs: int,
        learning_rate: float,
        training_mode: str = "production",
) -> LogisticFraudModel | XGBoostFraudModel:
    """Train the configured model type."""
    pipeline = FeaturePipeline().fit(dataset)
    feature_rows = pipeline.transform(dataset, mode=training_mode)
    _validate_feature_set(feature_rows, pipeline.get_training_features(training_mode), training_mode)
    if model_type == "logistic":
        model = _new_model(model_type, training_mode, list(feature_rows[0]) if feature_rows else [])
        assert isinstance(model, LogisticFraudModel)
        model.fit(feature_rows, dataset.y, epochs=epochs, learning_rate=learning_rate)
        return model
    if model_type == "xgboost":
        model = _new_model(model_type, training_mode, list(feature_rows[0]) if feature_rows else pipeline.get_training_features(training_mode))
        assert isinstance(model, XGBoostFraudModel)
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
        training_mode: str = "production",
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
            "trainingMode": training_mode,
            "featureSetUsed": list(weights),
        },
        "trainingMode": training_mode,
        "featureSetUsed": list(weights),
        "featureSchema": list(weights),
        "featureImportance": {name: abs(weight) for name, weight in weights.items()},
        "evaluation": evaluation or {},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(artifact, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_model_artifact(
        path: Path,
        model: LogisticFraudModel | XGBoostFraudModel,
        examples: int,
        evaluation: dict[str, object],
) -> None:
    """Persist any supported model with aligned artifact metadata."""
    if isinstance(model, LogisticFraudModel):
        write_artifact(
            path,
            model.bias,
            model.weights,
            examples,
            model_type="logistic",
            evaluation=evaluation,
            training_mode=model.training_mode,
        )
        return
    model.save(path, metadata={"examples": examples, "evaluation": evaluation})


def _new_model(
        model_type: str,
        training_mode: str,
        feature_schema: list[str],
) -> LogisticFraudModel | XGBoostFraudModel:
    if model_type == "logistic":
        model = LogisticFraudModel()
    elif model_type == "xgboost":
        model = XGBoostFraudModel()
    else:
        raise ValueError("model_type must be 'logistic' or 'xgboost'.")
    model.training_mode = training_mode
    model.feature_schema = list(feature_schema)
    if hasattr(model, "weights") and not getattr(model, "weights"):
        model.weights = {name: 0.0 for name in feature_schema}
    return model


def _stability_assessment(temporal_report: dict[str, object], out_of_time_report: dict[str, object]) -> dict[str, object]:
    temporal_optimal = temporal_report["optimalThreshold"]
    out_of_time_optimal = out_of_time_report["optimalThreshold"]
    temporal_cost = temporal_report["costEvaluation"]["optimalCostThreshold"]
    out_of_time_cost = out_of_time_report["costEvaluation"]["optimalCostThreshold"]
    return {
        "prAucDelta": round(float(temporal_report["prAuc"]) - float(out_of_time_report["prAuc"]), 6),
        "fraudCaptureDelta": round(float(temporal_optimal["fraudCaptureRate"]) - float(out_of_time_optimal["fraudCaptureRate"]), 6),
        "falsePositiveRateDelta": round(float(out_of_time_optimal["falsePositiveRate"]) - float(temporal_optimal["falsePositiveRate"]), 6),
        "expectedCostDelta": round(float(out_of_time_cost["totalCost"]) - float(temporal_cost["totalCost"]), 6),
    }


def _validate_feature_set(rows: list[dict[str, float]], expected_features: list[str], training_mode: str) -> None:
    expected = list(expected_features)
    for row in rows:
        actual = list(row)
        if actual != expected:
            missing = [name for name in expected if name not in row]
            unexpected = [name for name in actual if name not in expected]
            raise ValueError(
                f"{training_mode} training feature schema mismatch; "
                f"missing={missing}; unexpected={unexpected}; actual={actual}; expected={expected}"
            )
