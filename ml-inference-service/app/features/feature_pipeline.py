from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from math import log2, sqrt
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT


class FeaturePipeline:
    """Normalizes fraud scoring features for training and inference."""

    FEATURE_NAMES = FEATURE_CONTRACT.ml_feature_names
    PRODUCTION_FEATURE_NAMES = FEATURE_CONTRACT.production_inference_features

    def __init__(self) -> None:
        self._user_mean_amounts: dict[str, float] = {}
        self._known_devices: dict[str, set[str]] = {}
        self._known_countries: dict[str, set[str]] = {}

    def fit(self, dataset: Any) -> FeaturePipeline:
        """Fit pipeline state from a dataset.

        Existing Java-enriched payloads use fixed normalization. Raw synthetic
        sequences use fit state as fallback for single-event inference.
        """
        rows = getattr(dataset, "X", dataset)
        amounts_by_user: dict[str, list[float]] = defaultdict(list)
        devices_by_user: dict[str, set[str]] = defaultdict(set)
        countries_by_user: dict[str, set[str]] = defaultdict(set)

        for row in rows:
            if not self._is_raw_event(row):
                continue
            user_id = str(row.get("user_id", "unknown"))
            raw = row["raw_transaction"]
            amounts_by_user[user_id].append(self._number(raw.get("amount")))
            devices_by_user[user_id].add(str(raw.get("deviceId", "")))
            countries_by_user[user_id].add(str(raw.get("country", "")))

        self._user_mean_amounts = {
            user_id: sum(amounts) / len(amounts)
            for user_id, amounts in amounts_by_user.items()
            if amounts
        }
        self._known_devices = dict(devices_by_user)
        self._known_countries = dict(countries_by_user)
        return self

    def transform(self, dataset: Any) -> list[dict[str, float]]:
        """Transform every feature row in a dataset into model-ready features."""
        rows = getattr(dataset, "X", dataset)
        if rows and all(self._is_raw_event(row) for row in rows):
            return self._transform_raw_sequence(rows)
        return [self.transform_single(row) for row in rows]

    def transform_single(self, event: dict[str, Any]) -> dict[str, float]:
        """Transform one scoring event into the model feature schema."""
        if self._is_raw_event(event):
            return self._raw_features(event, history=[])

        feature_flags = event.get("featureFlags") or []
        amount_sum = self._money_amount(event.get("recentAmountSum"))
        return {
            "recentTransactionCount": min(self._number(event.get("recentTransactionCount")) / 10.0, 1.0),
            "recentAmountSum": min(amount_sum / 10000.0, 1.0),
            "transactionVelocityPerMinute": min(self._number(event.get("transactionVelocityPerMinute")) / 5.0, 1.0),
            "transactionVelocityPerHour": min(self._number(event.get("transactionVelocityPerHour")) / 20.0, 1.0),
            "transactionVelocityPerDay": min(self._number(event.get("transactionVelocityPerDay")) / 80.0, 1.0),
            "recentAmountAverage": min(self._money_amount(event.get("recentAmountAverage")) / 5000.0, 1.0),
            "recentAmountStdDev": min(self._money_amount(event.get("recentAmountStdDev")) / 5000.0, 1.0),
            "amountDeviationFromUserMean": min(self._number(event.get("amountDeviationFromUserMean")) / 5.0, 1.0),
            "merchantEntropy": min(self._number(event.get("merchantEntropy")) / 4.0, 1.0),
            "countryEntropy": min(self._number(event.get("countryEntropy")) / 3.0, 1.0),
            "merchantFrequency7d": min(self._number(event.get("merchantFrequency7d")) / 12.0, 1.0),
            "deviceNovelty": self._flag(event.get("deviceNovelty")),
            "countryMismatch": self._flag(event.get("countryMismatch")),
            "proxyOrVpnDetected": self._flag(event.get("proxyOrVpnDetected")),
            "highRiskFlagCount": min(len(feature_flags) / 6.0, 1.0) if isinstance(feature_flags, list) else 0.0,
            "rapidTransferBurst": self._rapid_transfer_burst(event, feature_flags),
        }

    def validate_production_snapshot(self, event: dict[str, Any]) -> dict[str, Any]:
        """Report production feature compatibility for a Java-enriched snapshot."""
        if self._is_raw_event(event):
            return {
                "compatible": True,
                "source": "raw_sequence",
                "missingRequiredFeatures": [],
                "trainingOnlyFeatures": self._features_by_availability("trainingOnly"),
            }
        required = [
            name for name in self.PRODUCTION_FEATURE_NAMES
            if FEATURE_CONTRACT.feature_availability.get(name) == "providedByJava"
        ]
        missing = [name for name in required if name not in event]
        return {
            "compatible": not missing,
            "source": "java_enriched_snapshot",
            "missingRequiredFeatures": missing,
            "providedByJava": self._features_by_availability("providedByJava"),
            "derivedInPython": self._features_by_availability("derivedInPython"),
            "trainingOnlyFeatures": self._features_by_availability("trainingOnly"),
        }

    def _features_by_availability(self, availability: str) -> list[str]:
        return [
            name for name, value in FEATURE_CONTRACT.feature_availability.items()
            if value == availability
        ]

    def _transform_raw_sequence(self, rows: list[dict[str, Any]]) -> list[dict[str, float]]:
        history_by_user: dict[str, list[dict[str, Any]]] = defaultdict(list)
        transformed_by_id: dict[int, dict[str, float]] = {}
        ordered = sorted(enumerate(rows), key=lambda item: self._timestamp(item[1]))

        for original_index, row in ordered:
            user_id = str(row.get("user_id", "unknown"))
            history = history_by_user[user_id]
            transformed_by_id[original_index] = self._raw_features(row, history)
            history.append(row)

        return [transformed_by_id[index] for index in range(len(rows))]

    def _raw_features(self, event: dict[str, Any], history: list[dict[str, Any]]) -> dict[str, float]:
        raw = event["raw_transaction"]
        occurred_at = self._timestamp(event)
        amount = self._number(raw.get("amount"))
        recent_minute = self._recent(history, occurred_at, seconds=60)
        recent_hour = self._recent(history, occurred_at, seconds=3600)
        recent_day = self._recent(history, occurred_at, seconds=86400)
        recent_week = self._recent(history, occurred_at, seconds=604800)
        recent_amounts = [self._number(row["raw_transaction"].get("amount")) for row in recent_day]
        user_id = str(event.get("user_id", "unknown"))
        historical_amounts = [self._number(row["raw_transaction"].get("amount")) for row in history]
        user_mean = self._user_mean_amounts.get(user_id) or self._mean(historical_amounts) or amount
        merchants = [str(row["raw_transaction"].get("merchantId", "")) for row in recent_week]
        countries = [str(row["raw_transaction"].get("country", "")) for row in recent_week]
        known_devices = {str(row["raw_transaction"].get("deviceId", "")) for row in history} or self._known_devices.get(user_id, set())
        known_countries = {str(row["raw_transaction"].get("country", "")) for row in history} or self._known_countries.get(user_id, set())
        scenario = str(event.get("metadata", {}).get("scenario", ""))

        return {
            "recentTransactionCount": min(len(recent_day) / 10.0, 1.0),
            "recentAmountSum": min(sum(recent_amounts) / 10000.0, 1.0),
            "transactionVelocityPerMinute": min(len(recent_minute) / 5.0, 1.0),
            "transactionVelocityPerHour": min(len(recent_hour) / 20.0, 1.0),
            "transactionVelocityPerDay": min(len(recent_day) / 80.0, 1.0),
            "recentAmountAverage": min(self._mean(recent_amounts) / 5000.0, 1.0),
            "recentAmountStdDev": min(self._stddev(recent_amounts) / 5000.0, 1.0),
            "amountDeviationFromUserMean": min(abs(amount - user_mean) / max(user_mean, 1.0) / 5.0, 1.0),
            "merchantEntropy": min(self._entropy(merchants) / 4.0, 1.0),
            "countryEntropy": min(self._entropy(countries) / 3.0, 1.0),
            "merchantFrequency7d": min(self._merchant_frequency(raw, recent_week) / 12.0, 1.0),
            "deviceNovelty": 1.0 if known_devices and str(raw.get("deviceId", "")) not in known_devices else 0.0,
            "countryMismatch": 1.0 if known_countries and str(raw.get("country", "")) not in known_countries else 0.0,
            "proxyOrVpnDetected": self._flag(raw.get("proxyOrVpnDetected")),
            "highRiskFlagCount": self._raw_high_risk_flags(event, amount, user_mean),
            "rapidTransferBurst": 1.0 if scenario == "rapid_transfer_burst" else 0.0,
        }

    def _recent(self, history: list[dict[str, Any]], occurred_at: datetime, seconds: int) -> list[dict[str, Any]]:
        return [
            row for row in history
            if 0 <= (occurred_at - self._timestamp(row)).total_seconds() <= seconds
        ]

    def _timestamp(self, event: dict[str, Any]) -> datetime:
        value = str(event.get("timestamp"))
        return datetime.fromisoformat(value)

    def _merchant_frequency(self, raw: dict[str, Any], recent_week: list[dict[str, Any]]) -> float:
        merchant_id = raw.get("merchantId")
        return sum(1 for row in recent_week if row["raw_transaction"].get("merchantId") == merchant_id)

    def _raw_high_risk_flags(self, event: dict[str, Any], amount: float, user_mean: float) -> float:
        raw = event["raw_transaction"]
        flags = [
            self._flag(raw.get("proxyOrVpnDetected")),
            1.0 if amount > user_mean * 3.0 else 0.0,
            1.0 if str(event.get("metadata", {}).get("scenario", "")) in {"account_takeover", "card_testing"} else 0.0,
        ]
        return min(sum(flags) / 6.0, 1.0)

    def _is_raw_event(self, event: dict[str, Any]) -> bool:
        return isinstance(event.get("raw_transaction"), dict)

    def _mean(self, values: list[float]) -> float:
        return sum(values) / len(values) if values else 0.0

    def _stddev(self, values: list[float]) -> float:
        if len(values) < 2:
            return 0.0
        mean = self._mean(values)
        return sqrt(sum((value - mean) ** 2 for value in values) / len(values))

    def _entropy(self, values: list[str]) -> float:
        if not values:
            return 0.0
        counts: dict[str, int] = defaultdict(int)
        for value in values:
            counts[value] += 1
        total = len(values)
        return -sum((count / total) * log2(count / total) for count in counts.values())

    def _rapid_transfer_burst(self, event: dict[str, Any], feature_flags: Any) -> float:
        if isinstance(feature_flags, list) and "RAPID_PLN_20K_BURST" in feature_flags:
            return 1.0
        if event.get("rapidTransferFraudCaseCandidate") is True:
            return 1.0
        return 1.0 if self._number(event.get("rapidTransferTotalPln")) >= 20_000.0 else 0.0

    def _money_amount(self, value: Any) -> float:
        if isinstance(value, dict):
            return self._number(value.get("amount"))
        return self._number(value)

    def _number(self, value: Any, default: float = 0.0) -> float:
        if isinstance(value, bool) or value is None:
            return default
        try:
            return max(float(value), 0.0)
        except (TypeError, ValueError):
            return default

    def _flag(self, value: Any) -> float:
        return 1.0 if value is True else 0.0
