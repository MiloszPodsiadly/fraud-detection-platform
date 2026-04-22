from __future__ import annotations

import random
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from app.data.dataset import Dataset


COUNTRIES = ["PL", "DE", "CZ", "FR", "ES", "IT", "NL", "SE"]
MERCHANT_CATEGORIES = ["GROCERY", "FUEL", "RESTAURANT", "ECOMMERCE", "TRAVEL", "DIGITAL_GOODS"]
CHANNELS = ["POS", "ECOMMERCE", "MOBILE_APP"]


@dataclass(frozen=True)
class UserProfile:
    """Synthetic user's stable normal-behavior profile."""

    user_id: str
    avg_transaction_amount: float
    frequency_per_day: float
    preferred_countries: tuple[str, ...]
    device_stability: float
    preferred_categories: tuple[str, ...]
    home_timezone_offset: int


class NormalBehaviorSimulator:
    """Generates reproducible user transaction sequences without fraud injection."""

    def __init__(self, seed: int, start_time: datetime | None = None) -> None:
        self.rng = random.Random(seed)
        self.start_time = start_time or datetime(2026, 1, 1, tzinfo=timezone.utc)

    def build_profiles(self, user_count: int) -> list[UserProfile]:
        """Create stable user profiles for sequence generation."""
        return [self._profile(index) for index in range(user_count)]

    def generate(self, count: int, user_count: int) -> Dataset:
        """Generate normal transaction sequences with placeholder labels."""
        profiles = self.build_profiles(user_count)
        per_user_counts = self._allocate_counts(count, profiles)
        transactions: list[dict[str, Any]] = []

        for profile, profile_count in zip(profiles, per_user_counts):
            transactions.extend(self._transactions_for_user(profile, profile_count))

        transactions.sort(key=lambda item: (item["timestamp"], item["user_id"], item["transaction_id"]))
        return Dataset(
            X=transactions,
            y=[0 for _ in transactions],
            metadata={
                "source": "normal-user-behavior-sequences",
                "label": "placeholder",
                "fraud_injected": False,
                "user_count": user_count,
                "start_time": self.start_time.isoformat(),
            },
        )

    def generate_with_fraud(self, count: int, user_count: int, fraud_ratio: float = 0.02) -> Dataset:
        """Generate user sequences with realistic labelled fraud scenarios."""
        if not 0.01 <= fraud_ratio <= 0.03:
            raise ValueError("fraud_ratio must be between 0.01 and 0.03.")

        profiles = self.build_profiles(user_count)
        fraud_count = max(1, round(count * fraud_ratio)) if count > 0 else 0
        legitimate_anomaly_count = max(1, round(count * 0.01)) if count >= 100 else 0
        normal_count = max(count - fraud_count - legitimate_anomaly_count, 0)
        per_user_counts = self._allocate_counts(normal_count, profiles)
        transactions: list[dict[str, Any]] = []

        for profile, profile_count in zip(profiles, per_user_counts):
            normal_transactions = self._transactions_for_user(profile, profile_count)
            for transaction in normal_transactions:
                transaction["label"] = False
            transactions.extend(normal_transactions)

        scenario_counts: dict[str, int] = {"normal_behavior": len(transactions)}
        fraud_scenarios = [
            "account_takeover",
            "card_testing",
            "rapid_transfer_burst",
            "low_and_slow",
        ]
        for index in range(fraud_count):
            scenario = fraud_scenarios[index % len(fraud_scenarios)]
            profile = self.rng.choice(profiles)
            transaction = self._fraud_transaction(profile, scenario, index)
            transactions.append(transaction)
            scenario_counts[scenario] = scenario_counts.get(scenario, 0) + 1

        for index in range(legitimate_anomaly_count):
            profile = self.rng.choice(profiles)
            transaction = self._legitimate_anomaly(profile, index)
            transactions.append(transaction)
            scenario_counts["legitimate_anomaly"] = scenario_counts.get("legitimate_anomaly", 0) + 1

        transactions = transactions[:count]
        transactions.sort(key=lambda item: (item["timestamp"], item["user_id"], item["transaction_id"]))
        return Dataset(
            X=transactions,
            y=[int(row["label"] is True) for row in transactions],
            metadata={
                "source": "fraud-injected-user-behavior-sequences",
                "fraud_ratio": fraud_ratio,
                "actual_fraud_ratio": round(
                    sum(1 for row in transactions if row["label"] is True) / len(transactions),
                    4,
                ) if transactions else 0.0,
                "fraud_injected": True,
                "scenario_counts": scenario_counts,
                "user_count": user_count,
                "start_time": self.start_time.isoformat(),
            },
        )

    def _profile(self, index: int) -> UserProfile:
        country_count = self.rng.choices([1, 2, 3], weights=[0.68, 0.25, 0.07], k=1)[0]
        category_count = self.rng.choices([2, 3, 4], weights=[0.45, 0.40, 0.15], k=1)[0]
        return UserProfile(
            user_id=f"user-{index + 1:05d}",
            avg_transaction_amount=round(self.rng.lognormvariate(4.0, 0.55), 2),
            frequency_per_day=self.rng.uniform(0.4, 5.5),
            preferred_countries=tuple(self.rng.sample(COUNTRIES, country_count)),
            device_stability=self.rng.uniform(0.78, 0.99),
            preferred_categories=tuple(self.rng.sample(MERCHANT_CATEGORIES, category_count)),
            home_timezone_offset=self.rng.randint(-1, 2),
        )

    def _allocate_counts(self, count: int, profiles: list[UserProfile]) -> list[int]:
        if not profiles:
            return []
        weights = [profile.frequency_per_day for profile in profiles]
        allocations = [0 for _ in profiles]
        for _ in range(count):
            selected = self.rng.choices(range(len(profiles)), weights=weights, k=1)[0]
            allocations[selected] += 1
        return allocations

    def _transactions_for_user(self, profile: UserProfile, count: int) -> list[dict[str, Any]]:
        current_time = self.start_time + timedelta(hours=self.rng.uniform(0, 24))
        transactions: list[dict[str, Any]] = []
        stable_device_id = f"device-{profile.user_id}-primary"

        for index in range(count):
            current_time += self._next_gap(profile)
            amount = self._amount(profile, current_time)
            device_id = stable_device_id
            if self.rng.random() > profile.device_stability:
                device_id = f"device-{profile.user_id}-{self.rng.randint(2, 5)}"

            category = self.rng.choice(profile.preferred_categories)
            country = self.rng.choice(profile.preferred_countries)
            channel = self._channel(category)
            transaction_id = f"txn-{profile.user_id}-{index + 1:06d}"
            transactions.append(
                {
                    "user_id": profile.user_id,
                    "timestamp": current_time.isoformat(),
                    "transaction_id": transaction_id,
                    "raw_transaction": {
                        "amount": amount,
                        "currency": "PLN",
                        "merchantCategory": category,
                        "merchantId": f"merchant-{category.lower()}-{self.rng.randint(1, 80):03d}",
                        "country": country,
                        "deviceId": device_id,
                        "channel": channel,
                        "proxyOrVpnDetected": self.rng.random() < 0.015,
                    },
                    "label": None,
                    "metadata": {
                        "scenario": "normal_behavior",
                        "avgTransactionAmount": profile.avg_transaction_amount,
                        "frequencyPerDay": profile.frequency_per_day,
                        "deviceStability": profile.device_stability,
                    },
                }
            )
        return transactions

    def _next_gap(self, profile: UserProfile) -> timedelta:
        mean_hours = max(24.0 / profile.frequency_per_day, 0.25)
        seasonal_factor = self.rng.uniform(0.75, 1.35)
        hours = self.rng.expovariate(1.0 / (mean_hours * seasonal_factor))
        return timedelta(hours=max(hours, 0.02))

    def _amount(self, profile: UserProfile, occurred_at: datetime) -> float:
        weekday_multiplier = 1.18 if occurred_at.weekday() in {4, 5} else 1.0
        monthly_multiplier = 1.12 if occurred_at.day <= 5 else 1.0
        spike_multiplier = self.rng.uniform(1.8, 3.8) if self.rng.random() < 0.025 else 1.0
        noise = self.rng.lognormvariate(0.0, 0.35)
        amount = profile.avg_transaction_amount * weekday_multiplier * monthly_multiplier * spike_multiplier * noise
        return round(max(amount, 2.0), 2)

    def _channel(self, category: str) -> str:
        if category in {"ECOMMERCE", "DIGITAL_GOODS"}:
            return self.rng.choices(CHANNELS, weights=[0.05, 0.55, 0.40], k=1)[0]
        if category == "TRAVEL":
            return self.rng.choices(CHANNELS, weights=[0.25, 0.45, 0.30], k=1)[0]
        return self.rng.choices(CHANNELS, weights=[0.70, 0.10, 0.20], k=1)[0]

    def _fraud_transaction(self, profile: UserProfile, scenario: str, index: int) -> dict[str, Any]:
        occurred_at = self.start_time + timedelta(days=self.rng.uniform(2, 30), minutes=index)
        base = profile.avg_transaction_amount
        country = self.rng.choice(profile.preferred_countries)
        device_id = f"device-{profile.user_id}-primary"
        amount = base * self.rng.uniform(1.1, 2.8)
        category = self.rng.choice(profile.preferred_categories)
        proxy = self.rng.random() < 0.04
        burst_key = None

        if scenario == "account_takeover":
            country = self._new_country(profile)
            device_id = f"device-{profile.user_id}-takeover-{index % 5}"
            amount = base * self.rng.uniform(2.4, 5.8)
            proxy = self.rng.random() < 0.70
        elif scenario == "card_testing":
            amount = self.rng.uniform(2.0, 35.0)
            category = self.rng.choice(["DIGITAL_GOODS", "ECOMMERCE"])
            burst_key = f"card-test-{profile.user_id}-{index // 4}"
        elif scenario == "rapid_transfer_burst":
            amount = base * self.rng.uniform(3.0, 7.0)
            category = "TRANSFER"
            burst_key = f"transfer-burst-{profile.user_id}-{index // 3}"
        elif scenario == "low_and_slow":
            amount = base * self.rng.uniform(1.25, 2.25) * (1.0 + (index % 5) * 0.08)
            category = self.rng.choice(("ECOMMERCE", "DIGITAL_GOODS", *profile.preferred_categories))
            proxy = self.rng.random() < 0.12

        return self._scenario_transaction(
            profile=profile,
            index=index,
            occurred_at=occurred_at,
            amount=round(max(amount, 2.0), 2),
            category=category,
            country=country,
            device_id=device_id,
            scenario=scenario,
            label=True,
            proxy=proxy,
            burst_key=burst_key,
        )

    def _legitimate_anomaly(self, profile: UserProfile, index: int) -> dict[str, Any]:
        occurred_at = self.start_time + timedelta(days=self.rng.uniform(1, 30), hours=self.rng.uniform(0, 24))
        amount = profile.avg_transaction_amount * self.rng.uniform(3.0, 7.5)
        category = self.rng.choice(("TRAVEL", "ECOMMERCE", *profile.preferred_categories))
        country = self.rng.choice(profile.preferred_countries)
        return self._scenario_transaction(
            profile=profile,
            index=index,
            occurred_at=occurred_at,
            amount=round(max(amount, 2.0), 2),
            category=category,
            country=country,
            device_id=f"device-{profile.user_id}-primary",
            scenario="legitimate_anomaly",
            label=False,
            proxy=False,
            burst_key=None,
        )

    def _scenario_transaction(
            self,
            profile: UserProfile,
            index: int,
            occurred_at: datetime,
            amount: float,
            category: str,
            country: str,
            device_id: str,
            scenario: str,
            label: bool,
            proxy: bool,
            burst_key: str | None,
    ) -> dict[str, Any]:
        return {
            "user_id": profile.user_id,
            "timestamp": occurred_at.isoformat(),
            "transaction_id": f"txn-{profile.user_id}-{scenario}-{index + 1:06d}",
            "raw_transaction": {
                "amount": amount,
                "currency": "PLN",
                "merchantCategory": category,
                "merchantId": f"merchant-{category.lower()}-{self.rng.randint(1, 80):03d}",
                "country": country,
                "deviceId": device_id,
                "channel": self._channel(category),
                "proxyOrVpnDetected": proxy,
            },
            "label": label,
            "metadata": {
                "scenario": scenario,
                "scenarioDebug": {
                    "avgTransactionAmount": profile.avg_transaction_amount,
                    "preferredCountries": list(profile.preferred_countries),
                    "deviceStability": profile.device_stability,
                    "burstKey": burst_key,
                },
            },
        }

    def _new_country(self, profile: UserProfile) -> str:
        candidates = [country for country in COUNTRIES if country not in profile.preferred_countries]
        return self.rng.choice(candidates or COUNTRIES)


def generate_normal_behavior(count: int, seed: int, user_count: int = 250) -> Dataset:
    """Generate normal user transaction sequences with label placeholders."""
    if count < 0:
        raise ValueError("count must be non-negative.")
    if user_count <= 0:
        raise ValueError("user_count must be positive.")
    effective_user_count = min(user_count, max(count, 1))
    return NormalBehaviorSimulator(seed).generate(count, effective_user_count)


def generate_fraud_behavior(
        count: int,
        seed: int,
        user_count: int = 250,
        fraud_ratio: float = 0.02,
) -> Dataset:
    """Generate user transaction sequences with labelled fraud scenarios."""
    if count < 0:
        raise ValueError("count must be non-negative.")
    if user_count <= 0:
        raise ValueError("user_count must be positive.")
    effective_user_count = min(user_count, max(count, 1))
    return NormalBehaviorSimulator(seed).generate_with_fraud(count, effective_user_count, fraud_ratio)


def generate_examples(count: int, seed: int) -> Dataset:
    """Generate labelled user behavior sequences.

    The normalized training generator is kept separately as
    generate_legacy_training_examples until Prompt 4 introduces raw-event feature engineering.
    """
    return generate_fraud_behavior(count, seed)


def generate_legacy_training_examples(count: int, seed: int) -> Dataset:
    """Generate the existing synthetic model-training examples."""
    rng = random.Random(seed)
    features: list[dict[str, float]] = []
    labels: list[int] = []
    scenarios = [
        ("normal", 0.91),
        ("rapid_transfer_seed", 0.04),
        ("new_device", 0.01),
        ("high_proxy_purchase", 0.01),
        ("country_mismatch", 0.01),
        ("account_takeover", 0.01),
        ("rapid_transfer_burst", 0.01),
    ]
    names = [name for name, _ in scenarios]
    weights = [weight for _, weight in scenarios]

    for _ in range(count):
        scenario = rng.choices(names, weights=weights, k=1)[0]
        row, label = example_for(scenario, rng)
        features.append(row)
        labels.append(label)

    return Dataset(
        X=features,
        y=labels,
        metadata={
            "source": "synthetic-fraud-scenarios",
            "seed": seed,
            "scenarios": dict(scenarios),
        },
    )


def example_for(scenario: str, rng: random.Random) -> tuple[dict[str, float], int]:
    """Create one normalized training example for a named scenario."""
    if scenario == "normal":
        return (
            {
                "recentTransactionCount": rng.uniform(0.0, 0.25),
                "recentAmountSum": rng.uniform(0.0, 0.18),
                "transactionVelocityPerMinute": rng.uniform(0.0, 0.12),
                "merchantFrequency7d": rng.uniform(0.0, 0.35),
                "deviceNovelty": 0.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": 0.0,
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "new_device":
        return (
            {
                "recentTransactionCount": rng.uniform(0.05, 0.35),
                "recentAmountSum": rng.uniform(0.03, 0.25),
                "transactionVelocityPerMinute": rng.uniform(0.04, 0.20),
                "merchantFrequency7d": rng.uniform(0.0, 0.45),
                "deviceNovelty": 1.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": rng.choice([0.0, 1.0]),
                "highRiskFlagCount": rng.uniform(0.15, 0.35),
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "rapid_transfer_seed":
        return (
            {
                "recentTransactionCount": rng.uniform(0.10, 0.20),
                "recentAmountSum": rng.uniform(0.45, 0.55),
                "transactionVelocityPerMinute": rng.uniform(0.20, 0.40),
                "merchantFrequency7d": rng.uniform(0.0, 0.20),
                "deviceNovelty": 0.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": 0.0,
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "country_mismatch":
        return (
            {
                "recentTransactionCount": rng.uniform(0.05, 0.45),
                "recentAmountSum": rng.uniform(0.05, 0.35),
                "transactionVelocityPerMinute": rng.uniform(0.05, 0.35),
                "merchantFrequency7d": rng.uniform(0.0, 0.50),
                "deviceNovelty": 1.0,
                "countryMismatch": 1.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": rng.uniform(0.30, 0.55),
                "rapidTransferBurst": 0.0,
            },
            0,
        )
    if scenario == "high_proxy_purchase":
        return (
            {
                "recentTransactionCount": rng.uniform(0.35, 0.80),
                "recentAmountSum": rng.uniform(0.35, 0.95),
                "transactionVelocityPerMinute": rng.uniform(0.30, 0.80),
                "merchantFrequency7d": rng.uniform(0.25, 0.85),
                "deviceNovelty": 1.0,
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 1.0,
                "highRiskFlagCount": rng.uniform(0.50, 0.85),
                "rapidTransferBurst": rng.choice([0.0, 1.0]),
            },
            1,
        )
    if scenario == "rapid_transfer_burst":
        return (
            {
                "recentTransactionCount": rng.uniform(0.20, 0.35),
                "recentAmountSum": rng.uniform(0.95, 1.0),
                "transactionVelocityPerMinute": rng.uniform(0.40, 0.65),
                "merchantFrequency7d": rng.uniform(0.0, 0.20),
                "deviceNovelty": rng.choice([0.0, 0.0, 1.0]),
                "countryMismatch": 0.0,
                "proxyOrVpnDetected": 0.0,
                "highRiskFlagCount": rng.uniform(0.10, 0.35),
                "rapidTransferBurst": 1.0,
            },
            1,
        )
    return (
        {
            "recentTransactionCount": rng.uniform(0.55, 1.0),
            "recentAmountSum": rng.uniform(0.45, 1.0),
            "transactionVelocityPerMinute": rng.uniform(0.45, 1.0),
            "merchantFrequency7d": rng.uniform(0.20, 1.0),
            "deviceNovelty": 1.0,
            "countryMismatch": 1.0,
            "proxyOrVpnDetected": 1.0,
            "highRiskFlagCount": rng.uniform(0.70, 1.0),
            "rapidTransferBurst": rng.choice([0.0, 1.0]),
        },
        1,
    )
