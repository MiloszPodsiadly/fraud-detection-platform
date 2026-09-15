from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from math import isfinite
from math import log2, sqrt
from typing import Any

from app.features.feature_contract import FEATURE_CONTRACT


SUPPORTED_CURRENCIES = {"PLN", "EUR", "USD", "GBP"}
MAX_RECENT_TRANSACTION_COUNT = 1_000_000
MAX_TRANSACTION_VELOCITY_PER_MINUTE = 1_000_000.0
MAX_RECENT_AMOUNT_SUM_PLN = 999_999_999_999.99
RATE_CONSISTENCY_TOLERANCE = 0.0001


class FeaturePipeline:
    """Normalizes fraud scoring features for training and inference."""

    FEATURE_NAMES = FEATURE_CONTRACT.ml_feature_names
    PRODUCTION_FEATURE_NAMES = FEATURE_CONTRACT.production_inference_features
    TRAINING_MODES = {"full", "production"}

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

    def get_training_features(self, mode: str = "production") -> list[str]:
        """Return the ordered feature schema allowed for a training mode."""
        if mode not in self.TRAINING_MODES:
            raise ValueError("training_mode must be 'full' or 'production'.")
        if mode == "production":
            features = list(self.PRODUCTION_FEATURE_NAMES)
            training_only = set(self._features_by_availability("trainingOnly"))
            invalid = [name for name in features if name in training_only]
            if invalid:
                raise ValueError(f"production training features include training-only fields: {invalid}")
            return features
        return list(self.FEATURE_NAMES)

    def transform(self, dataset: Any, mode: str = "full") -> list[dict[str, float]]:
        """Transform every feature row in a dataset into model-ready features."""
        rows = getattr(dataset, "X", dataset)
        if rows and all(self._is_raw_event(row) for row in rows):
            return [self._select_features(row, mode) for row in self._transform_raw_sequence(rows)]
        return [self.transform_single(row, mode=mode) for row in rows]

    def transform_single(self, event: dict[str, Any], mode: str = "full") -> dict[str, float]:
        """Transform one scoring event into the model feature schema."""
        if self._is_raw_event(event):
            return self._select_features(self._raw_features(event, history=[]), mode)

        amount_sum_pln = self._strict_amount(event.get("recentAmountSumPln"), "recentAmountSumPln")
        rapid_transfer_burst = self._rapid_transfer_burst(event)
        features = {
            "recentTransactionCount": min(self._strict_integer(event.get("recentTransactionCount"), "recentTransactionCount") / 10.0, 1.0),
            "recentAmountSumPln": min(amount_sum_pln / 10000.0, 1.0),
            "transactionVelocityPerMinute": min(self._strict_float(event.get("transactionVelocityPerMinute"), "transactionVelocityPerMinute") / 5.0, 1.0),
            "transactionVelocityPerHour": min(self._number(event.get("transactionVelocityPerHour")) / 20.0, 1.0),
            "transactionVelocityPerDay": min(self._number(event.get("transactionVelocityPerDay")) / 80.0, 1.0),
            "recentAmountAverage": min(self._money_amount(event.get("recentAmountAverage")) / 5000.0, 1.0),
            "recentAmountStdDev": min(self._money_amount(event.get("recentAmountStdDev")) / 5000.0, 1.0),
            "amountDeviationFromUserMean": min(self._number(event.get("amountDeviationFromUserMean")) / 5.0, 1.0),
            "merchantEntropy": min(self._number(event.get("merchantEntropy")) / 4.0, 1.0),
            "countryEntropy": min(self._number(event.get("countryEntropy")) / 3.0, 1.0),
            "merchantFrequency7d": min(self._strict_integer(event.get("merchantFrequency7d"), "merchantFrequency7d") / 12.0, 1.0),
            "deviceNovelty": self._strict_boolean(event.get("deviceNovelty"), "deviceNovelty"),
            "countryMismatch": self._strict_boolean(event.get("countryMismatch"), "countryMismatch"),
            "proxyOrVpnDetected": self._strict_boolean(event.get("proxyOrVpnDetected"), "proxyOrVpnDetected"),
            "highRiskFlagCount": self._high_risk_fact_count(event, amount_sum_pln, rapid_transfer_burst),
            "rapidTransferBurst": rapid_transfer_burst,
        }
        return self._select_features(features, mode)

    def validate_production_snapshot(self, event: dict[str, Any]) -> dict[str, Any]:
        """Report production feature compatibility for a Java-enriched snapshot."""
        if self._is_raw_event(event):
            return {
                "compatible": False,
                "source": "raw_sequence",
                "missingRequiredFeatures": [],
                "invalidFeatures": {
                    "raw_transaction": "raw_sequence_not_allowed_for_production_inference",
                },
                "providedByJava": self._features_by_availability("providedByJava"),
                "derivedInPython": self._features_by_availability("derivedInPython"),
                "trainingOnlyFeatures": self._features_by_availability("trainingOnly"),
            }
        required = [
            name for name in FEATURE_CONTRACT.java_enriched_feature_names
            if FEATURE_CONTRACT.feature_availability.get(name) == "providedByJava"
        ]
        missing = [name for name in required if name not in event]
        invalid = self._invalid_production_features(event, missing)
        return {
            "compatible": not missing and not invalid,
            "source": "java_enriched_snapshot",
            "missingRequiredFeatures": missing,
            "invalidFeatures": invalid,
            "providedByJava": self._features_by_availability("providedByJava"),
            "derivedInPython": self._features_by_availability("derivedInPython"),
            "trainingOnlyFeatures": self._features_by_availability("trainingOnly"),
        }

    def _features_by_availability(self, availability: str) -> list[str]:
        return [
            name for name, value in FEATURE_CONTRACT.feature_availability.items()
            if value == availability
        ]

    def _select_features(self, features: dict[str, float], mode: str) -> dict[str, float]:
        allowed = self.get_training_features(mode)
        missing = [name for name in allowed if name not in features]
        if missing:
            raise ValueError(f"feature transform missing required {mode} features: {missing}")
        return {name: features[name] for name in allowed}

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
        recent_minute = [*self._recent(history, occurred_at, seconds=60), event]
        recent_hour = [*self._recent(history, occurred_at, seconds=3600), event]
        recent_day = [*self._recent(history, occurred_at, seconds=86400), event]
        recent_week = [*self._recent(history, occurred_at, seconds=604800), event]
        recent_day_amounts = [self._number(row["raw_transaction"].get("amount")) for row in recent_day]
        recent_minute_amount = sum(self._number(row["raw_transaction"].get("amount")) for row in recent_minute)
        user_id = str(event.get("user_id", "unknown"))
        historical_amounts = [self._number(row["raw_transaction"].get("amount")) for row in history]
        user_mean = self._user_mean_amounts.get(user_id) or self._mean(historical_amounts) or amount
        merchants = [str(row["raw_transaction"].get("merchantId", "")) for row in recent_week]
        countries = [str(row["raw_transaction"].get("country", "")) for row in recent_week]
        known_devices = {str(row["raw_transaction"].get("deviceId", "")) for row in history} or self._known_devices.get(user_id, set())
        known_countries = {str(row["raw_transaction"].get("country", "")) for row in history} or self._known_countries.get(user_id, set())
        merchant_frequency = self._merchant_frequency(raw, recent_week)
        device_novelty = 1.0 if known_devices and str(raw.get("deviceId", "")) not in known_devices else 0.0
        country_mismatch = 1.0 if known_countries and str(raw.get("country", "")) not in known_countries else 0.0
        proxy_or_vpn = self._flag(raw.get("proxyOrVpnDetected"))
        rapid_transfer_burst = 1.0 if len(recent_minute) >= 2 and recent_minute_amount >= 20_000.0 else 0.0
        high_risk_fact_count = min(sum([
            device_novelty,
            country_mismatch,
            proxy_or_vpn,
            1.0 if merchant_frequency >= 5.0 else 0.0,
            1.0 if len(recent_minute) >= 2 and recent_minute_amount >= 5_000.0 else 0.0,
            rapid_transfer_burst,
        ]) / 6.0, 1.0)

        return {
            "recentTransactionCount": min(len(recent_minute) / 10.0, 1.0),
            "recentAmountSumPln": min(recent_minute_amount / 10000.0, 1.0),
            "transactionVelocityPerMinute": min(len(recent_minute) / 5.0, 1.0),
            "transactionVelocityPerHour": min(len(recent_hour) / 20.0, 1.0),
            "transactionVelocityPerDay": min(len(recent_day) / 80.0, 1.0),
            "recentAmountAverage": min(self._mean(recent_day_amounts) / 5000.0, 1.0),
            "recentAmountStdDev": min(self._stddev(recent_day_amounts) / 5000.0, 1.0),
            "amountDeviationFromUserMean": min(abs(amount - user_mean) / max(user_mean, 1.0) / 5.0, 1.0),
            "merchantEntropy": min(self._entropy(merchants) / 4.0, 1.0),
            "countryEntropy": min(self._entropy(countries) / 3.0, 1.0),
            "merchantFrequency7d": min(merchant_frequency / 12.0, 1.0),
            "deviceNovelty": device_novelty,
            "countryMismatch": country_mismatch,
            "proxyOrVpnDetected": proxy_or_vpn,
            "highRiskFlagCount": high_risk_fact_count,
            "rapidTransferBurst": rapid_transfer_burst,
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

    def _high_risk_fact_count(self, event: dict[str, Any], amount_sum: float, rapid_transfer_burst: float) -> float:
        facts = [
            self._strict_boolean(event.get("deviceNovelty"), "deviceNovelty"),
            self._strict_boolean(event.get("countryMismatch"), "countryMismatch"),
            self._strict_boolean(event.get("proxyOrVpnDetected"), "proxyOrVpnDetected"),
            1.0 if self._strict_integer(event.get("merchantFrequency7d"), "merchantFrequency7d") >= 5 else 0.0,
            1.0 if self._strict_integer(event.get("recentTransactionCount"), "recentTransactionCount") >= 2 and amount_sum >= 5_000.0 else 0.0,
            rapid_transfer_burst,
        ]
        return min(sum(facts) / 6.0, 1.0)

    def _rapid_transfer_burst(self, event: dict[str, Any]) -> float:
        if self._strict_integer(event.get("recentTransactionCount"), "recentTransactionCount") < 2:
            return 0.0
        if not self._canonical_one_minute_window(event.get("recentTransactionCountWindow")):
            return 0.0
        if not self._canonical_one_minute_window(event.get("recentAmountSumWindow")):
            return 0.0
        return 1.0 if self._strict_amount(event.get("recentAmountSumPln"), "recentAmountSumPln") >= 20_000.0 else 0.0

    def _canonical_one_minute_window(self, value: Any) -> bool:
        return str(value) == "PT1M"

    def _money_amount(self, value: Any) -> float:
        if isinstance(value, dict):
            return self._number(value.get("amount"))
        return self._number(value)

    def _invalid_production_features(self, event: dict[str, Any], missing: list[str]) -> dict[str, str]:
        if missing:
            return {}
        checks = [
            ("recentTransactionCount", lambda: self._strict_integer(event.get("recentTransactionCount"), "recentTransactionCount")),
            ("merchantFrequency7d", lambda: self._strict_integer(event.get("merchantFrequency7d"), "merchantFrequency7d")),
            ("transactionVelocityPerMinute", lambda: self._strict_float(event.get("transactionVelocityPerMinute"), "transactionVelocityPerMinute")),
            ("recentAmountSumPln", lambda: self._strict_amount(event.get("recentAmountSumPln"), "recentAmountSumPln")),
            ("currentTransactionAmountPln", lambda: self._strict_amount(event.get("currentTransactionAmountPln"), "currentTransactionAmountPln")),
            ("deviceNovelty", lambda: self._strict_boolean(event.get("deviceNovelty"), "deviceNovelty")),
            ("countryMismatch", lambda: self._strict_boolean(event.get("countryMismatch"), "countryMismatch")),
            ("proxyOrVpnDetected", lambda: self._strict_boolean(event.get("proxyOrVpnDetected"), "proxyOrVpnDetected")),
            ("recentTransactionCountWindow", lambda: self._strict_window(event.get("recentTransactionCountWindow"), "recentTransactionCountWindow")),
            ("recentAmountSumWindow", lambda: self._strict_window(event.get("recentAmountSumWindow"), "recentAmountSumWindow")),
            ("currency", lambda: self._strict_currency(event.get("currency"))),
        ]
        invalid: dict[str, str] = {}
        for name, check in checks:
            try:
                check()
            except ValueError as exc:
                invalid[name] = str(exc)
        if not invalid:
            count = self._strict_integer(event.get("recentTransactionCount"), "recentTransactionCount")
            rate = self._strict_float(event.get("transactionVelocityPerMinute"), "transactionVelocityPerMinute")
            if abs(rate - float(count)) > RATE_CONSISTENCY_TOLERANCE:
                invalid["transactionVelocityPerMinute"] = "inconsistent_with_recentTransactionCount"
        return invalid

    def _strict_integer(self, value: Any, name: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError(f"{name}_must_be_integer")
        if value < 0 or value > MAX_RECENT_TRANSACTION_COUNT:
            raise ValueError(f"{name}_out_of_bounds")
        return value

    def _strict_float(self, value: Any, name: str) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{name}_must_be_number")
        number = float(value)
        if not isfinite(number) or number < 0.0 or number > MAX_TRANSACTION_VELOCITY_PER_MINUTE:
            raise ValueError(f"{name}_out_of_bounds")
        return number

    def _strict_amount(self, value: Any, name: str) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f"{name}_must_be_number")
        number = float(value)
        if not isfinite(number) or number < 0.0 or number > MAX_RECENT_AMOUNT_SUM_PLN:
            raise ValueError(f"{name}_out_of_bounds")
        return number

    def _strict_boolean(self, value: Any, name: str) -> float:
        if not isinstance(value, bool):
            raise ValueError(f"{name}_must_be_boolean")
        return 1.0 if value else 0.0

    def _strict_window(self, value: Any, name: str) -> str:
        if value != "PT1M":
            raise ValueError(f"{name}_must_be_PT1M")
        return value

    def _strict_currency(self, value: Any) -> str:
        if not isinstance(value, str) or value.upper() not in SUPPORTED_CURRENCIES:
            raise ValueError("currency_unsupported")
        return value.upper()

    def _number(self, value: Any, default: float = 0.0) -> float:
        if isinstance(value, bool) or value is None:
            return default
        try:
            return max(float(value), 0.0)
        except (TypeError, ValueError):
            return default

    def _flag(self, value: Any) -> float:
        return 1.0 if value is True else 0.0
