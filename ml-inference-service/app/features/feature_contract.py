from __future__ import annotations

import json
from pathlib import Path
from typing import Any


_FALLBACK_ML_FEATURE_NAMES = [
    "recentTransactionCount",
    "recentAmountSum",
    "transactionVelocityPerMinute",
    "transactionVelocityPerHour",
    "transactionVelocityPerDay",
    "recentAmountAverage",
    "recentAmountStdDev",
    "amountDeviationFromUserMean",
    "merchantEntropy",
    "countryEntropy",
    "merchantFrequency7d",
    "deviceNovelty",
    "countryMismatch",
    "proxyOrVpnDetected",
    "highRiskFlagCount",
    "rapidTransferBurst",
]


class FeatureContract:
    """Shared feature contract loaded from the Java common-events resource."""

    def __init__(self, contract: dict[str, Any]) -> None:
        self.version = str(contract.get("version", "fallback"))
        self.ml_feature_names = self._list(contract.get("mlFeatureNames"), _FALLBACK_ML_FEATURE_NAMES)
        self.java_enriched_feature_names = self._list(contract.get("javaEnrichedFeatureNames"), [])
        self.feature_flags = self._list(contract.get("featureFlags"), [])
        self.production_inference_features = self._list(
            contract.get("productionInferenceFeatures"),
            self.ml_feature_names,
        )
        self.normalization = contract.get("normalization") if isinstance(contract.get("normalization"), dict) else {}
        self.feature_availability = contract.get("featureAvailability") if isinstance(contract.get("featureAvailability"), dict) else {}

    @staticmethod
    def load() -> FeatureContract:
        """Load the repo-owned JSON feature contract with a local fallback."""
        path = _contract_path()
        if path and path.exists():
            return FeatureContract(json.loads(path.read_text(encoding="utf-8")))
        return FeatureContract({"mlFeatureNames": _FALLBACK_ML_FEATURE_NAMES})

    def _list(self, value: Any, fallback: list[str]) -> list[str]:
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            return list(fallback)
        return list(value)


def _contract_path() -> Path | None:
    current = Path(__file__).resolve()
    for parent in current.parents:
        candidate = parent / "common-events" / "src" / "main" / "resources" / "feature-contract" / "fraud-feature-contract.json"
        if candidate.exists():
            return candidate
    return None


FEATURE_CONTRACT = FeatureContract.load()
