from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


PRODUCTION_CONTRACT_ENV = "FRAUD_FEATURE_CONTRACT_PATH"
ALLOW_FALLBACK_ENV = "FRAUD_FEATURE_CONTRACT_ALLOW_FALLBACK"
PACKAGED_CONTRACT_PATH = Path("/app/contracts/fraud-feature-contract.json")
_FALLBACK_ML_FEATURE_NAMES = [
    "recentTransactionCount",
    "recentAmountSumPln",
    "transactionVelocityPerMinute",
    "merchantFrequency7d",
    "deviceNovelty",
    "countryMismatch",
    "proxyOrVpnDetected",
    "suspiciousFactRatio",
    "rapidTransferBurst",
]


class FeatureContract:
    """Shared feature contract loaded from the Java common-events resource."""

    @staticmethod
    def load() -> FeatureContract:
        """Load the repo-owned JSON feature contract with an explicit test-only fallback."""
        path = _contract_path()
        if path and path.exists():
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Feature contract JSON is invalid: {path}") from exc
            if not isinstance(payload, dict):
                raise RuntimeError(f"Feature contract must be a JSON object: {path}")
            return FeatureContract(payload)
        if _fallback_allowed():
            return FeatureContract(
                {
                    "version": "fallback",
                    "mlFeatureNames": _FALLBACK_ML_FEATURE_NAMES,
                    "productionInferenceFeatures": _FALLBACK_ML_FEATURE_NAMES,
                },
                allow_fallback=True,
            )
        raise RuntimeError(
            "Canonical fraud feature contract is required. Set "
            f"{PRODUCTION_CONTRACT_ENV} or run from a repository checkout containing "
            "common-events/src/main/resources/feature-contract/fraud-feature-contract.json."
        )

    def __init__(self, contract: dict[str, Any], allow_fallback: bool = False) -> None:
        self.version = str(contract.get("version", "fallback"))
        self.ml_feature_names = self._list(contract.get("mlFeatureNames"), _FALLBACK_ML_FEATURE_NAMES)
        self.java_enriched_feature_names = self._list(contract.get("javaEnrichedFeatureNames"), [])
        self.production_inference_features = self._list(
            contract.get("productionInferenceFeatures"),
            self.ml_feature_names,
        )
        self.normalization = contract.get("normalization") if isinstance(contract.get("normalization"), dict) else {}
        self.feature_availability = contract.get("featureAvailability") if isinstance(contract.get("featureAvailability"), dict) else {}
        self.supported_currencies = self._list(contract.get("supportedCurrencies"), [])
        self.production_feature_semantics = (
            contract.get("productionFeatureSemantics")
            if isinstance(contract.get("productionFeatureSemantics"), dict)
            else {}
        )
        self.strict_typing_expectations = (
            contract.get("strictTypingExpectations")
            if isinstance(contract.get("strictTypingExpectations"), dict)
            else {}
        )
        self._validate(allow_fallback=allow_fallback)

    def _list(self, value: Any, fallback: list[str]) -> list[str]:
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            return list(fallback)
        return list(value)

    def _validate(self, allow_fallback: bool = False) -> None:
        if not self.version or self.version == "fallback":
            if allow_fallback:
                return
            raise RuntimeError("Feature contract version must be present and must not be fallback.")
        _require_unique_strings("mlFeatureNames", self.ml_feature_names)
        _require_unique_strings("productionInferenceFeatures", self.production_inference_features)
        if not self.production_inference_features:
            raise RuntimeError("Feature contract productionInferenceFeatures must not be empty.")
        missing = [name for name in self.production_inference_features if name not in self.ml_feature_names]
        if missing:
            raise RuntimeError(f"Production inference features missing from mlFeatureNames: {missing}")
        for name in self.production_inference_features:
            if name not in self.production_feature_semantics:
                raise RuntimeError(f"Missing production feature semantics for {name}.")
        if not self.supported_currencies:
            raise RuntimeError("Feature contract supportedCurrencies must not be empty.")
        if not isinstance(self.strict_typing_expectations.get("windows"), dict):
            raise RuntimeError("Feature contract strictTypingExpectations.windows must be present.")


def _contract_path() -> Path | None:
    configured = os.getenv(PRODUCTION_CONTRACT_ENV, "").strip()
    if configured:
        path = Path(configured)
        if not path.exists():
            raise RuntimeError(f"Configured feature contract does not exist: {path}")
        return path
    if PACKAGED_CONTRACT_PATH.exists():
        return PACKAGED_CONTRACT_PATH
    current = Path(__file__).resolve()
    for parent in current.parents:
        candidate = parent / "common-events" / "src" / "main" / "resources" / "feature-contract" / "fraud-feature-contract.json"
        if candidate.exists():
            return candidate
    return None


def _fallback_allowed() -> bool:
    return os.getenv(ALLOW_FALLBACK_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def _require_unique_strings(name: str, values: list[str]) -> None:
    if not values:
        raise RuntimeError(f"Feature contract {name} must not be empty.")
    duplicates = sorted({value for value in values if values.count(value) > 1})
    if duplicates:
        raise RuntimeError(f"Feature contract {name} contains duplicates: {duplicates}")


FEATURE_CONTRACT = FeatureContract.load()
