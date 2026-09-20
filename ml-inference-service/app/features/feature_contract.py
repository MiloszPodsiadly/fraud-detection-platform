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
_REQUIRED_CONTRACT_FIELDS = (
    "version",
    "mlFeatureNames",
    "javaEnrichedFeatureNames",
    "productionInferenceFeatures",
    "normalization",
    "featureAvailability",
    "supportedCurrencies",
    "productionFeatureSemantics",
    "strictTypingExpectations",
)


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
                _fallback_contract(),
                allow_fallback=True,
            )
        raise RuntimeError(
            "Canonical fraud feature contract is required. Set "
            f"{PRODUCTION_CONTRACT_ENV} or run from a repository checkout containing "
            "common-events/src/main/resources/feature-contract/fraud-feature-contract.json."
        )

    def __init__(self, contract: dict[str, Any], allow_fallback: bool = False) -> None:
        self._field_names = set(contract)
        self.version = _required_string(contract, "version")
        self.ml_feature_names = _required_string_list(contract, "mlFeatureNames")
        self.java_enriched_feature_names = _required_string_list(contract, "javaEnrichedFeatureNames")
        self.production_inference_features = _required_string_list(contract, "productionInferenceFeatures")
        self.normalization = _required_dict(contract, "normalization")
        self.feature_availability = _required_dict(contract, "featureAvailability")
        self.supported_currencies = _required_string_list(contract, "supportedCurrencies")
        self.production_feature_semantics = _required_dict(contract, "productionFeatureSemantics")
        self.strict_typing_expectations = _required_dict(contract, "strictTypingExpectations")
        self._validate(allow_fallback=allow_fallback)

    def _validate(self, allow_fallback: bool = False) -> None:
        if not self.version or (self.version == "fallback" and not allow_fallback):
            raise RuntimeError("Feature contract version must be present and must not be fallback.")
        for field in _REQUIRED_CONTRACT_FIELDS:
            if field not in self._raw_contract_fields:
                raise RuntimeError(f"Feature contract {field} must be present.")
        _require_unique_strings("mlFeatureNames", self.ml_feature_names)
        _require_unique_strings("javaEnrichedFeatureNames", self.java_enriched_feature_names)
        _require_unique_strings("productionInferenceFeatures", self.production_inference_features)
        _require_unique_strings("supportedCurrencies", self.supported_currencies)
        if not self.production_inference_features:
            raise RuntimeError("Feature contract productionInferenceFeatures must not be empty.")
        missing = [name for name in self.production_inference_features if name not in self.ml_feature_names]
        if missing:
            raise RuntimeError(f"Production inference features missing from mlFeatureNames: {missing}")
        semantic_keys = set(self.production_feature_semantics)
        if semantic_keys != set(self.production_inference_features):
            raise RuntimeError(
                "Feature contract productionFeatureSemantics must exactly match productionInferenceFeatures: "
                f"missing={sorted(set(self.production_inference_features) - semantic_keys)}; "
                f"unexpected={sorted(semantic_keys - set(self.production_inference_features))}"
            )
        known_features = (
            set(self.ml_feature_names)
            | set(self.java_enriched_feature_names)
            | set(self.production_inference_features)
            | set(self.feature_availability)
        )
        unknown_normalization = sorted(set(self.normalization) - known_features)
        if unknown_normalization:
            raise RuntimeError(f"Feature contract normalization contains unknown features: {unknown_normalization}")
        declared_features = (
            set(self.ml_feature_names)
            | set(self.java_enriched_feature_names)
            | set(self.production_inference_features)
            | set(self.normalization)
            | set(self.production_feature_semantics)
        )
        unknown_availability = sorted(set(self.feature_availability) - declared_features)
        if unknown_availability:
            raise RuntimeError(f"Feature contract featureAvailability contains unknown features: {unknown_availability}")
        for name in self.production_inference_features:
            if name not in self.production_feature_semantics:
                raise RuntimeError(f"Missing production feature semantics for {name}.")
        if not self.supported_currencies:
            raise RuntimeError("Feature contract supportedCurrencies must not be empty.")
        if not isinstance(self.strict_typing_expectations.get("windows"), dict):
            raise RuntimeError("Feature contract strictTypingExpectations.windows must be present.")

    @property
    def _raw_contract_fields(self) -> set[str]:
        return set(self._field_names)


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


def _fallback_contract() -> dict[str, Any]:
    return {
        "version": "fallback",
        "mlFeatureNames": _FALLBACK_ML_FEATURE_NAMES,
        "javaEnrichedFeatureNames": [
            "recentTransactionCount",
            "recentTransactionCountWindow",
            "recentAmountSumWindow",
            "recentAmountSumPln",
            "currentTransactionAmountPln",
            "transactionVelocityPerMinute",
            "merchantFrequency7d",
            "deviceNovelty",
            "countryMismatch",
            "proxyOrVpnDetected",
            "currency",
        ],
        "productionInferenceFeatures": _FALLBACK_ML_FEATURE_NAMES,
        "normalization": {name: {"source": "fallback"} for name in _FALLBACK_ML_FEATURE_NAMES},
        "featureAvailability": {
            "recentTransactionCount": "providedByJava",
            "recentTransactionCountWindow": "providedByJava",
            "recentAmountSumWindow": "providedByJava",
            "recentAmountSumPln": "providedByJava",
            "currentTransactionAmountPln": "providedByJava",
            "currency": "providedByJava",
            "transactionVelocityPerMinute": "providedByJava",
            "merchantFrequency7d": "providedByJava",
            "deviceNovelty": "providedByJava",
            "countryMismatch": "providedByJava",
            "proxyOrVpnDetected": "providedByJava",
            "suspiciousFactRatio": "derivedInPython",
            "rapidTransferBurst": "derivedInPython",
        },
        "supportedCurrencies": ["PLN", "EUR", "USD", "GBP"],
        "productionFeatureSemantics": {
            name: {"type": "fallback", "unit": "fallback", "window": "fallback", "source": "test fallback"}
            for name in _FALLBACK_ML_FEATURE_NAMES
        },
        "strictTypingExpectations": {
            "windows": {
                "recentTransactionCountWindow": "PT1M",
                "recentAmountSumWindow": "PT1M",
            }
        },
    }


def _required_string(contract: dict[str, Any], name: str) -> str:
    value = contract.get(name)
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(f"Feature contract {name} must be a non-blank string.")
    return value


def _required_string_list(contract: dict[str, Any], name: str) -> list[str]:
    value = contract.get(name)
    if not isinstance(value, list):
        raise RuntimeError(f"Feature contract {name} must be a list.")
    invalid = [item for item in value if not isinstance(item, str) or not item.strip()]
    if invalid:
        raise RuntimeError(f"Feature contract {name} must contain only non-blank strings.")
    return list(value)


def _required_dict(contract: dict[str, Any], name: str) -> dict[str, Any]:
    value = contract.get(name)
    if not isinstance(value, dict):
        raise RuntimeError(f"Feature contract {name} must be an object.")
    return dict(value)


def _require_unique_strings(name: str, values: list[str]) -> None:
    if not values:
        raise RuntimeError(f"Feature contract {name} must not be empty.")
    duplicates = sorted({value for value in values if values.count(value) > 1})
    if duplicates:
        raise RuntimeError(f"Feature contract {name} contains duplicates: {duplicates}")


FEATURE_CONTRACT = FeatureContract.load()
